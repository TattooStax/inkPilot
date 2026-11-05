#!/usr/bin/env python3
"""Lexical navigation index for the dated InkPilot audit delta.

This is deliberately not a call graph. It locates changed declarations and
same-named references for a human to inspect.
"""

import json
import re
import subprocess
from pathlib import Path


BASE = "0b2d8fc08803a2aac029283266a71dfea27cac40"
OUTPUT = Path("docs/audit-index/delta-source-2026-11-05.jsonl")
SUFFIXES = (".kt", ".cpp", ".h", ".kts")
GENERIC = {
    "onCreate", "onResume", "onPause", "onDestroy", "onReceive",
    "onNewIntent", "onActivityResult", "onRequestPermissionsResult",
    "onSaveInstanceState", "view", "load", "save", "create", "open",
    "close", "run", "start", "stop", "copy", "insert", "update", "delete",
    "get", "set", "send", "render", "parse", "read", "write", "result",
}
FUNCTION = re.compile(r"\bfun\s+(?:<[^>]+>\s*)?(?:[\w?<>, ]+\.)?([A-Za-z_]\w*)\s*\(")
TYPE = re.compile(r"\b(?:class|interface|object|typealias|enum\s+class)\s+([A-Za-z_]\w*)")
NATIVE = re.compile(r"\b(?:[A-Za-z_]\w*[:*<> ]*\s+)+([A-Za-z_]\w*)\s*\(")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True)


def words(path: str) -> set[str]:
    return set(re.findall(r"\b[A-Za-z_]\w*\b", Path(path).read_text(errors="replace")))


def declarations(path: str) -> list[tuple[int, str, str]]:
    result = []
    for number, line in enumerate(Path(path).read_text(errors="replace").splitlines(), 1):
        if line.lstrip().startswith(("//", "*", "@")):
            continue
        for expression, kind in ((FUNCTION, "fun"), (TYPE, "type")):
            result.extend((number, match.group(1), kind) for match in expression.finditer(line))
        if path.endswith((".cpp", ".h")) and ("{" in line or ";" in line):
            match = NATIVE.search(line)
            if match and match.group(1) not in {"if", "for", "while", "switch", "return"}:
                result.append((number, match.group(1), "native"))
    return sorted(set(result))


def changed_ranges() -> dict[str, set[int]]:
    current = None
    ranges: dict[str, set[int]] = {}
    for line in git("diff", "--unified=0", BASE, "HEAD", "--", "agent-core/src/main",
                    "app/src/main", "local-inference/src/main", "transport/src/main").splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            ranges[current] = set()
        elif line.startswith("@@ ") and current:
            match = HUNK.match(line)
            if match:
                start, count = int(match.group(1)), int(match.group(2) or 1)
                ranges[current].update(range(start, start + max(count, 1)))
    return ranges


def main() -> None:
    rows = [line.split("\t", 1) for line in git("diff", "--name-status", BASE, "HEAD").splitlines()
            if "\t" in line]
    sources = [(status, path) for status, path in rows
               if "/src/main/" in path and path.endswith(SUFFIXES)]
    paths = git("ls-files").splitlines()
    production = [path for path in paths if "/src/main/" in path and path.endswith(SUFFIXES)]
    tests = [path for path in paths if ("/src/test/" in path or "/src/androidTest/" in path)
             and path.endswith(SUFFIXES)]
    production_words = {path: words(path) for path in production}
    test_words = {path: words(path) for path in tests}
    changed = changed_ranges()

    def references(name: str, source: str) -> dict | None:
        if len(name) < 5 or name in GENERIC:
            return None
        callers = [path for path, tokens in production_words.items() if path != source and name in tokens]
        checks = [path for path, tokens in test_words.items() if name in tokens]
        return {
            "productionFileCount": len(callers), "productionExamples": callers[:5],
            "testFileCount": len(checks), "testExamples": checks[:5],
        }

    records = []
    for status, path in sources:
        lines = Path(path).read_text(errors="replace").splitlines()
        delta = changed.get(path, set())
        symbols = declarations(path)
        selected = set(symbols if status.startswith("A") else (declaration for declaration in symbols
                       if declaration[0] in delta))
        if not status.startswith("A"):
            for line in delta:
                preceding = [declaration for declaration in symbols if declaration[0] <= line]
                if preceding:
                    selected.add(preceding[-1])
        entries = []
        seen = set()
        for line, name, kind in sorted(selected):
            if (name, kind) in seen:
                continue
            seen.add((name, kind))
            entries.append({"name": name, "kind": kind, "declarationLine": line,
                            "candidateReferences": references(name, path)})
        records.append({
            "status": status, "path": path,
            "changedLineCount": len(lines) if status.startswith("A") else len(delta),
            "nearestChangedDeclarations": entries,
        })
    OUTPUT.write_text("".join(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n"
                              for row in records))
    print(f"{OUTPUT}: {len(records)} source files, "
          f"{sum(len(row['nearestChangedDeclarations']) for row in records)} declaration locators")


if __name__ == "__main__":
    main()
