#!/usr/bin/env python3
"""Fail on new Detekt findings without concealing the reviewed existing debt."""
import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[1]
CONFIG_FILES = ("detekt.yml", "app/detekt-baseline.xml", "app/build.gradle.kts", "gradle/libs.versions.toml")
DETEKT_VERSION = "1.23.8"


def config_hashes(root: Path) -> dict[str, str]:
    return {name: sha256((root / name).read_bytes()).hexdigest() for name in CONFIG_FILES}


def results(path: Path, root: Path) -> Counter:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("version") != "2.1.0" or len(document.get("runs", [])) != 1:
        raise ValueError("Expected one SARIF 2.1.0 Detekt run")
    counted = Counter()
    for run in document["runs"]:
        driver = run.get("tool", {}).get("driver", {})
        if (driver.get("name"), driver.get("semanticVersion")) != ("detekt", DETEKT_VERSION):
            raise ValueError("Unexpected Detekt analyzer or version")
        if not isinstance(run.get("results"), list):
            raise ValueError("Detekt results array is missing")
        for finding in run["results"]:
            location = finding["locations"][0]["physicalLocation"]
            uri = location["artifactLocation"]["uri"]
            parsed = urlparse(uri)
            if parsed.scheme not in ("", "file"):
                raise ValueError("Unsupported source URI in Detekt report")
            source = Path(unquote(parsed.path))
            if not source.is_absolute():
                source = root / source
            source = source.resolve()
            relative = source.relative_to(root.resolve()).as_posix()
            lines = source.read_text(encoding="utf-8").splitlines()
            line_number = location["region"]["startLine"]
            if line_number < 1 or line_number > len(lines):
                raise ValueError(f"Invalid source line in Detekt report: {relative}:{line_number}")
            normalized = " ".join(lines[line_number - 1].split())
            message = finding["message"]["text"].replace(str(root.resolve()), "<repo>")
            key = (relative, finding["ruleId"], message,
                   sha256(normalized.encode("utf-8")).hexdigest())
            counted[key] += 1
    return counted


def load_baseline(path: Path, config_root: Path = ROOT) -> Counter:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    if not rows or rows[0].get("version") != 1 or rows[0].get("analyzer") != f"detekt {DETEKT_VERSION}":
        raise ValueError("Unrecognized Detekt debt baseline")
    if rows[0].get("config_sha256") != config_hashes(config_root):
        raise ValueError("Detekt rules, baseline, build configuration, or version changed; review debt")
    allowed = Counter()
    for row in rows[1:]:
        key = (row["path"], row["rule"], row["message"], row["source_sha256"])
        if key in allowed or not isinstance(row["count"], int) or row["count"] <= 0:
            raise ValueError("Duplicate or invalid Detekt debt entry")
        allowed[key] = row["count"]
    return allowed


def write_baseline(path: Path, counted: Counter, config_root: Path = ROOT) -> None:
    rows = [dict(path=k[0], rule=k[1], message=k[2], source_sha256=k[3], count=v)
            for k, v in sorted(counted.items())]
    header = {"version": 1, "analyzer": f"detekt {DETEKT_VERSION}",
              "config_sha256": config_hashes(config_root),
              "policy": "Existing Detekt debt; no new fingerprint or increased count"}
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False, sort_keys=True)
                              for row in [header, *rows]) + "\n", encoding="utf-8")


def check(current: Counter, allowed: Counter) -> list[tuple]:
    return sorted((key, count, allowed.get(key, 0)) for key, count in current.items()
                  if count > allowed.get(key, 0))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sarif", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, default=ROOT / "app/detekt-debt.jsonl")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()
    current = results(args.sarif, args.root)
    if args.update:
        write_baseline(args.baseline, current, args.root)
        print(f"Recorded {sum(current.values())} Detekt baseline findings")
        return 0
    added = check(current, load_baseline(args.baseline, args.root))
    for key, count, old_count in added[:25]:
        print(f"NEW {key[0]} {key[1]} ({count} now; {old_count} allowed)")
    print(f"Detekt debt: {sum(current.values())} current findings; {len(added)} new/increased fingerprints")
    return 1 if added else 0


if __name__ == "__main__":
    raise SystemExit(main())
