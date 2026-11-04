#!/usr/bin/env python3
"""Offline synthetic parser-boundary qualification, never a tool executor.

No generation claim: parserAccepted is supplied by a pinned native adapter.
The legacy XML sample is separate syntax evidence, with grammar unenforced.
Only audited QAD v1 is approved; other LFM3 variants remain unqualified.
"""
import argparse
import hashlib
import json
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
    args = parser.parse_args()
    artifact = qualify(args.model)
    fixtures = [
        ("ordinary", '{"contactId":"synthetic-a"}', "lookup_thread", True, "SYNTAX_ONLY"),
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
    print(json.dumps({"qualificationVersion": 1, "artifact": artifact,
                      "mode": "synthetic_parser_boundary_fixtures", "generation": "UNEXECUTED",
                      "grammarEnforced": False, "executionEnabled": False, "cases": cases}, indent=2))
    return 0 if artifact["approved"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
