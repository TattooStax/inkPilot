#!/usr/bin/env bash
# Disposable emulator proof that the enabled launcher icon opens the studio shell cold.
set -euo pipefail
serial=${1:?Usage: inkpilot-launcher-cold-start.sh emulator-PORT}
[[ "$serial" =~ ^emulator-[0-9]+$ ]]
[[ $(adb -s "$serial" get-state | tr -d '\r') == device ]]
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
package=app.inkpilot.android.debug
alias_name=app.inkpilot.android.activities.SplashActivity.Green
ui=/data/local/tmp/inkpilot-launcher-cold-ui.xml
cleanup() {
  adb -s "$serial" shell rm -f "$ui" >/dev/null 2>&1 || true
  adb -s "$serial" shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT
mapfile -t launchers < <(adb -s "$serial" shell cmd package query-activities --brief --components --query-flags 0 \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$package" | tr -d '\r')
[[ ${#launchers[@]} -eq 1 && "${launchers[0]}" == "$package/$alias_name" ]] || {
  echo 'Expected one enabled inkPilot launcher alias' >&2; exit 1;
}
adb -s "$serial" shell am force-stop "$package"
[[ -z $(adb -s "$serial" shell pidof "$package" 2>/dev/null | tr -d '\r') ]]
started=$(adb -s "$serial" shell am start -W -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER -n "$package/$alias_name" 2>&1) || {
  echo 'Cold launcher could not start' >&2; exit 1;
}
[[ "$started" == *"Status: ok"* ]] || { echo 'Cold launcher did not report success' >&2; exit 1; }
for attempt in {1..12}; do
  if adb -s "$serial" shell uiautomator dump "$ui" >/dev/null 2>&1 &&
    adb -s "$serial" shell grep -q "resource-id=\"$package:id/inkpilot_tab_phone\"" "$ui" &&
    adb -s "$serial" shell grep -q "resource-id=\"$package:id/inkpilot_tab_calendar\"" "$ui"; then
    echo 'PASS: cold launcher opened the five-tab studio shell'
    exit 0
  fi
  sleep 0.2
done
echo 'Cold launcher did not reveal the studio shell' >&2
exit 1
