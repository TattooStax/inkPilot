#!/usr/bin/env bash
# Exact-class runner for disposable emulators. Role, permissions and model setup
# belong to the caller; this runner never changes them or collects UI/customer data.
set -euo pipefail
if [[ $# -ne 2 || ! $1 =~ ^emulator-[0-9]+$ || ! $2 =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]]; then
  echo 'Usage: run-inkpilot-test-class.sh emulator-PORT fully.qualified.TestClass' >&2
  exit 2
fi
serial=$1
class=$2
cd "$(dirname "${BASH_SOURCE[0]}")/.."
evidence="app/build/test-class-evidence/$class"
mkdir -p "$evidence"
printf 'FAIL %s (run incomplete)\n' "$class" > "$evidence/result.txt"
shopt -s nullglob
apks=(app/build/outputs/apk/foss/debug/*.apk)
tests=(app/build/outputs/apk/androidTest/foss/debug/*.apk)
if [[ ${#apks[@]} -ne 1 || ${#tests[@]} -ne 1 ]]; then
  echo 'Expected exactly one foss debug APK and one instrumentation APK; build them first.' >&2
  exit 2
fi
[[ $(adb -s "$serial" get-state) == device ]]
adb -s "$serial" install -r "${apks[0]}"
adb -s "$serial" install -r "${tests[0]}"
# Keep only a redacted verdict. Arbitrary test assertion text may contain message
# bodies, so the raw result exists only in a private temporary file until parsed.
result=$(mktemp)
trap 'rm -f "$result"' EXIT
if timeout 600 adb -s "$serial" shell am instrument -w -e class "$class" \
  app.inkpilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner > "$result" 2>&1 && \
  python3 scripts/inkpilot-device-result.py "$result" > /dev/null 2>&1; then
  printf 'PASS %s\n' "$class" | tee "$evidence/result.txt"
else
  printf 'FAIL %s (instrumentation failed; raw output withheld)\n' "$class" | tee "$evidence/result.txt" >&2
  exit 1
fi
