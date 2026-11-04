#!/usr/bin/env python3
"""Offline synthetic parser-boundary qualification, never a tool executor.

Runs the pinned native host adapter; fixture and generated evidence stay separate.
The legacy XML sample is separate syntax evidence, with grammar unenforced.
Only audited QAD v1 is approved; other LFM3 variants remain unqualified.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

SHA = "43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452"
SIZE = 1049615680


def qualify(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(131072), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    return {"bytes": path.stat().st_size, "sha256": actual,
            "approved": actual == SHA and path.stat().st_size == SIZE}


def classify(arguments, tool="lookup_thread", parsed=True):
    if not parsed:
        return "MALFORMED"
    if tool != "lookup_thread":
        return "UNKNOWN_TOOL"
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate")
            result[key] = value
        return result
    try:
        value = json.loads(arguments, object_pairs_hook=unique)
    except (ValueError, TypeError):
        return "MALFORMED"
    if not isinstance(value, dict) or set(value) != {"contactId"}:
        return "MALFORMED"
    if value["contactId"] not in ("synthetic-a", "synthetic-b"):
        return "MALFORMED"
    return "SYNTAX_ONLY" if value["contactId"] == "synthetic-a" else "CROSS_SCOPE"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--native", type=Path, required=True, help="Pinned inkpilot-tool-probe host target")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, nargs="*", default=[])
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    artifact = qualify(args.model)
    if not artifact["approved"]:
        print(json.dumps({"artifact": artifact, "verdict": "UNSUPPORTED_MODEL", "executionEnabled": False}))
        return 1
    fixtures = [
        ("ordinary", '{"contactId":"synthetic-a"}', "lookup_thread", True, "SYNTAX_ONLY"),
        ("escaped_json", '{"contactId":"synthetic-\\u0061"}', "lookup_thread", True, "SYNTAX_ONLY"),
        ("form_feed", '\f{"contactId":"synthetic-a"}', "lookup_thread", True, "MALFORMED"),
        ("vertical_tab", '\v{"contactId":"synthetic-a"}', "lookup_thread", True, "MALFORMED"),
        ("malformed_json", "{", "lookup_thread", True, "MALFORMED"),
        ("hallucinated_tool", "{}", "send_sms", True, "UNKNOWN_TOOL"),
        ("cross_scope", '{"contactId":"synthetic-b"}', "lookup_thread", True, "CROSS_SCOPE"),
        ("injection", '{"contactId":"synthetic-a","send":true}', "lookup_thread", True, "MALFORMED"),
        ("duplicate", '{"contactId":"synthetic-a","contactId":"synthetic-b"}', "lookup_thread", True, "MALFORMED"),
        ("malformed_xml", "{}", "lookup_thread", False, "MALFORMED"),
    ]
    cases = []
    for name, arguments, tool, parsed, expected in fixtures:
        verdict = classify(arguments, tool, parsed)
        assert verdict == expected
        cases.append({"name": name, "verdict": verdict, "passed": True})
    observed = []
    for name in ("ordinary", "cross_scope", "injection", "unknown_tool", "malformed_xml"):
        try:
            run = subprocess.run([str(args.native.resolve()), str(args.model.resolve()),
                                  str(args.output_dir.resolve()), name],
                                 capture_output=True, text=True, timeout=130, check=False)
            result = json.loads(run.stdout)
            calls = result["calls"]
            if run.returncode or not result["finished"] or result["elapsedMillis"] > 120000:
                verdict = "LIMIT_EXCEEDED"
            elif not result["parserAccepted"] or len(calls) != 1 or not result["contentEmpty"]:
                verdict = "MALFORMED"
            else:
                verdict = classify(calls[0]["arguments"], calls[0]["name"])
            result["verdict"] = verdict
            result["rawValidity"] = result["parserAccepted"] and result["finished"]
            result["schemaValidity"] = verdict == "SYNTAX_ONLY"
            observed.append(result)
        except (subprocess.TimeoutExpired, ValueError, KeyError, OSError):
            observed.append({"case": name, "verdict": "RUNTIME_FAILURE", "executionEnabled": False})
    template = args.output_dir / "model-template.txt"
    report = {"qualificationVersion": 1, "artifact": artifact,
              "llamaRevision": "7071ba378afa96dcd69c41e1ec2b4912364dbee4",
              "settings": {"contextTokens": 2048, "outputTokens": 256, "deadlineMillis": 120000,
                           "threads": 4, "seed": 42, "temperature": 0.1, "topK": 50,
                           "repetitionPenalty": 1.1, "grammarEnforced": False},
              "templateSha256": hashlib.sha256(template.read_bytes()).hexdigest() if template.exists() else None,
              "executionEnabled": False, "classifierFixtures": cases, "observed": observed,
              "failures": [{"case": x["case"], "verdict": x["verdict"]} for x in observed if x["verdict"] != "SYNTAX_ONLY"],
              "inventory": [{"filename": path.name, **qualify(path),
                             "reason": "exact audited artifact" if qualify(path)["approved"] else "unqualified artifact; no family incompatibility claim"}
                            for path in args.inventory],
              "claim": "bounded syntax probe only; no registry or reliability approval"}
    output = json.dumps(report, indent=2)
    (args.output_dir / "report.json").write_text(output + "\n")
    print(output)
    return 0 if all(x["verdict"] != "RUNTIME_FAILURE" for x in observed) else 1



if __name__ == "__main__":
    raise SystemExit(main())
