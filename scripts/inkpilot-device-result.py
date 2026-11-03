#!/usr/bin/env python3
"""Fail CI when adb exits successfully but AndroidJUnitRunner reports failure."""
import re
import sys
from pathlib import Path

result = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
failures = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "shortMsg=")
tests = re.search(r"\bOK \((\d+) tests?\)", result)
bad_status = re.search(r"INSTRUMENTATION_STATUS_CODE: -[1-4]\b", result)
if any(marker in result for marker in failures) or bad_status or tests is None or int(tests[1]) == 0:
    raise SystemExit("Android instrumentation did not report a nonempty passing test run")
print(f"Verified {tests[1]} passing Android tests")
