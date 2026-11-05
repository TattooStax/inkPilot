#!/usr/bin/env python3
"""Validate the actual Actions REST run response before trusting a beta artifact."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

WORKFLOW_PATH = ".github/workflows/inkpilot-build.yml"


def validate_run(record: dict, evidence: dict, repository: str, run_id: str) -> list[str]:
    errors: list[str] = []
    if not re.fullmatch(r"[0-9]+", run_id) or str(record.get("id")) != run_id:
        errors.append("candidate run ID differs from the API response")
    if record.get("conclusion") != "success" or record.get("event") != "workflow_dispatch":
        errors.append("candidate must be a successful manual build")
    if record.get("path") != WORKFLOW_PATH:
        errors.append("candidate came from an unexpected workflow path")
    source_repository = record.get("head_repository")
    if not isinstance(source_repository, dict) or source_repository.get("full_name") != repository:
        errors.append("candidate came from another repository")
    source = record.get("head_sha")
    if not isinstance(source, str) or not re.fullmatch(r"[0-9a-f]{40}", source) or source != evidence.get("candidate_source_sha"):
        errors.append("candidate source revision differs from physical evidence")
    if evidence.get("candidate_run_id") != run_id:
        errors.append("candidate run ID differs from physical evidence")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_json", type=Path)
    parser.add_argument("evidence_json", type=Path)
    parser.add_argument("repository")
    parser.add_argument("run_id")
    args = parser.parse_args()
    try:
        record = json.loads(args.run_json.read_text(encoding="utf-8"))
        evidence = json.loads(args.evidence_json.read_text(encoding="utf-8"))
        errors = validate_run(record, evidence, args.repository, args.run_id)
    except (OSError, ValueError, AttributeError, TypeError) as error:
        print(f"Invalid candidate run response or evidence: {error}", file=sys.stderr)
        return 1
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if errors:
        return 1
    print(record["head_sha"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
