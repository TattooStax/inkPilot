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
[[ $(adb -s "$serial" get-state) == device ]] || { echo 'Emulator unavailable' >&2; exit 2; }
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Test runner requires a disposable emulator' >&2; exit 2; }
adb -s "$serial" install -r "${apks[0]}" || { echo 'App installation failed' >&2; exit 2; }
adb -s "$serial" install -r "${tests[0]}" || { echo 'Test installation failed' >&2; exit 2; }
scripts/inkpilot-sms-role-preflight.sh "$serial" app.inkpilot.android.debug || { echo 'SMS role preflight failed' >&2; exit 2; }
# Keep only a redacted verdict; raw assertions stay in a private temporary file.
result=$(mktemp)
trap 'rm -f "$result"' EXIT
status=0
timeout 600 adb -s "$serial" shell am instrument -w -e class "$class" \
  app.inkpilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner > "$result" 2>&1 || status=$?
if ((status == 124 || status == 137)); then
  printf 'FAIL %s (instrumentation timeout; raw output withheld)\n' "$class" | tee "$evidence/result.txt" >&2
  exit 2
fi
if ((status == 0)) && python3 scripts/inkpilot-device-result.py "$result" > /dev/null 2>&1; then
  printf 'PASS %s\n' "$class" | tee "$evidence/result.txt"
else
  # Only a source-controlled method identifier and Kotlin filename/line may
  # leave the temporary raw result; assertions can contain customer data.
  site=$(grep -Eo '[A-Za-z_][A-Za-z0-9_]*Test\.kt:[0-9]+' "$result" | head -n 1 || true)
  # AndroidJUnitRunner's test identifier is a source-controlled method name.
  # Pair it with a failure code; never publish assertion text or stack values.
  method=$(awk '
    /^INSTRUMENTATION_STATUS: test=/ {
      current = substr($0, length("INSTRUMENTATION_STATUS: test=") + 1)
      sub(/\r$/, "", current)
    }
    /^INSTRUMENTATION_STATUS_CODE: -[12]\r?$/ {
      if (current ~ /^[A-Za-z_][A-Za-z0-9_]*$/) { print current; exit }
    }
  ' "$result")
  # Some runner failures omit the final status test field but still emit the
  # JUnit summary. Accept only its method identifier; keep the body private.
  if [[ -z $method ]]; then
    method=$(sed -nE 's/^Error in ([A-Za-z_][A-Za-z0-9_]*)\(.*/\1/p' "$result" | head -n 1)
  fi
  if [[ -n $method ]]; then
    printf 'FAIL %s#%s (%s; raw output withheld)\n' "$class" "$method" "${site:-instrumentation failed}" | tee "$evidence/result.txt" >&2
  else
    printf 'FAIL %s (%s; raw output withheld)\n' "$class" "${site:-instrumentation failed}" | tee "$evidence/result.txt" >&2
  fi
  exit 1
fi
