#!/usr/bin/env bash
# Repeat the shell navigation suite after other tests, retaining only bounded evidence.
set -euo pipefail
serial=emulator-5554
package=app.inkpilot.android.debug
result=0
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.OneShellAcceptanceTest || result=$?
evidence=app/build/test-class-evidence/studio-tab-window.json
if adb -s "$serial" exec-out run-as "$package" cat files/device-test-evidence/studio-tab-window.json \
    > "$evidence.partial" 2>/dev/null && python3 -m json.tool "$evidence.partial" >/dev/null 2>&1; then
  mv "$evidence.partial" "$evidence"
else
  rm -f "$evidence.partial"
fi
exit "$result"
