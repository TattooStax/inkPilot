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
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Test runner requires a disposable emulator' >&2; exit 2; }
adb -s "$serial" install -r "${apks[0]}"
adb -s "$serial" install -r "${tests[0]}"
scripts/inkpilot-sms-role-preflight.sh "$serial" app.inkpilot.android.debug
# Keep only a redacted verdict; raw assertions stay in a private temporary file.
result=$(mktemp)
trap 'rm -f "$result"' EXIT
if timeout 600 adb -s "$serial" shell am instrument -w -e class "$class" \
  app.inkpilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner > "$result" 2>&1 && \
  python3 scripts/inkpilot-device-result.py "$result" > /dev/null 2>&1; then
  printf 'PASS %s\n' "$class" | tee "$evidence/result.txt"
else
  # The disposable first-launch fixture may publish bounded numeric view
  # geometry; never copy instrumentation assertions or message text.
  if [[ "$class" == org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest ]]; then
    geometry=$(adb -s "$serial" shell run-as app.inkpilot.android.debug cat files/firstlaunch-geometry.csv 2>/dev/null | tr -d '\r' || true)
    python3 - "$geometry" "$evidence/geometry.csv" <<'PY_DIAG'
import re, sys
raw = sys.argv[1]
if re.fullmatch(r'-?\d+(,-?\d+){16}', raw):
    values = [int(item) for item in raw.split(',')]
    if all(-1 <= item <= 5000 for item in values):
        with open(sys.argv[2], 'w', encoding='ascii') as out:
            out.write('list_width,list_height,list_shown,list_visible,visible_width,visible_height,children,first,matched_texts,text_visible,row_height,row_visible,parent_height,footer_height,canvas_height,ime_visible,focus_edit\n')
            out.write(raw + '\n')
PY_DIAG
  fi
  # Only a Kotlin test filename and line number may leave the temporary raw
  # result. Assertion messages can contain customer text or phone numbers.
  site=$(grep -Eo '[A-Za-z_][A-Za-z0-9_]*Test\.kt:[0-9]+' "$result" | head -n 1 || true)
  printf 'FAIL %s (%s; raw output withheld)\n' "$class" "${site:-instrumentation failed}" | tee "$evidence/result.txt" >&2
  exit 1
fi
