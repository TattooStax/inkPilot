#!/usr/bin/env bash
set -euo pipefail

model_path="${1:?Usage: inkpilot-device-run.sh verified-model.gguf}"
serial=emulator-5554
[[ $(adb -s "$serial" get-state | tr -d '\r') == device ]]
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Device proof requires a disposable emulator' >&2; exit 2; }
export ANDROID_SERIAL="$serial"
package=app.inkpilot.android.debug
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
smoke=org.fossify.messages.inkpilot.smoke.LocalLoopSmokeTest
evidence=app/build/device-test-evidence
fixture_number=+12025550123
fixture_message='Can I book a consultation? inkPilot device proof.'
worker_message='Can I ask about a tattoo consultation? inkPilot worker wakeup proof.'
mkdir -p "$evidence"

collect() {
  adb logcat -b all -d > "$evidence/logcat.txt" 2>&1 || true
  adb shell dumpsys package "$package" > "$evidence/package.txt" 2>&1 || true
  adb shell cmd role get-role-holders --user 0 android.app.role.SMS > "$evidence/sms-role.txt" 2>&1 || true
  adb shell appops get "$package" > "$evidence/appops.txt" 2>&1 || true
  adb exec-out screencap -p > "$evidence/last-screen.png" 2>/dev/null || true
  adb shell uiautomator dump /sdcard/inkpilot-test-ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/inkpilot-test-ui.xml "$evidence/last-ui.xml" >/dev/null 2>&1 || true
  for artifact in local-loop.json intake-failure.json worker-wakeup.json \
    local-staff-attention.png tab-sms.png tab-phone.png tab-contacts.png tab-studio.png tab-calendar.png; do
    if adb shell run-as "$package" test -f "files/device-test-evidence/$artifact" && \
      adb exec-out run-as "$package" cat "files/device-test-evidence/$artifact" > "$evidence/$artifact.partial" 2>/dev/null; then
      mv "$evidence/$artifact.partial" "$evidence/$artifact"
    else
      rm -f "$evidence/$artifact.partial"
    fi
  done
}
trap collect EXIT

run_tests() {
  local name="$1" selector="$2" value="$3" excluded="${4:-}" exclusion=""
  if [[ -n "$excluded" ]]; then exclusion="-e notClass '$excluded'"; fi
  timeout 600 adb shell "am instrument -w -r -e $selector '$value' $exclusion -e fixtureMessage '$fixture_message' -e workerMessage '$worker_message' $runner" \
    | tee "$evidence/$name.txt"
  python3 scripts/inkpilot-device-result.py "$evidence/$name.txt"
}

# Instrumentation restarts the target process. Start observation before delivery.
observe_sms() {
  local name="$1" test_class="$2" marker="$3" number="$4" body="$5"
  adb shell run-as "$package" rm -f "files/device-test-evidence/$marker"
  run_tests "$name" class "$test_class" &
  local observer_pid=$! ready=false
  for attempt in $(seq 1 120); do
    if adb shell run-as "$package" test -f "files/device-test-evidence/$marker"; then
      ready=true
      break
    fi
    if ! kill -0 "$observer_pid" 2>/dev/null; then wait "$observer_pid"; return 1; fi
    sleep 0.5
  done
  if [[ "$ready" != true ]]; then
    kill "$observer_pid" 2>/dev/null || true
    return 1
  fi
  adb emu sms send "$number" "$body"
  wait "$observer_pid"
}

shopt -s nullglob
apks=(app/build/outputs/apk/foss/debug/*.apk)
tests=(app/build/outputs/apk/androidTest/foss/debug/*.apk)
test "${#apks[@]}" -eq 1
test "${#tests[@]}" -eq 1
adb install -r "${apks[0]}"
adb install -r "${tests[0]}"
adb shell cmd role add-role-holder --user 0 android.app.role.SMS "$package"
adb shell cmd role get-role-holders --user 0 android.app.role.SMS | tr -d '\r' | grep -Fx "$package"
for permission in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE POST_NOTIFICATIONS; do
  adb shell pm grant "$package" "android.permission.$permission"
done
scripts/inkpilot-sms-role-preflight.sh emulator-5554 "$package"
# First-launch preservation requires a pristine app/provider and runs first in
# the independent model-free baseline job. Never include it in this broad suite.
timeout 600 adb shell "am instrument -w -r -e package org.fossify.messages.inkpilot.data -e notClass org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest $runner" \
  | tee "$evidence/sqlite.txt"
python3 scripts/inkpilot-device-result.py "$evidence/sqlite.txt"
# These process-phase fixtures run only in the dedicated force-stop/JobScheduler
# shell sequences. A broad package run cannot establish their restart boundary.
run_tests studio package org.fossify.messages.inkpilot.studio \
  'org.fossify.messages.inkpilot.studio.StudioImageOrphanPreparationTest,org.fossify.messages.inkpilot.studio.StudioImageOrphanRecoveryTest,org.fossify.messages.inkpilot.studio.StudioReminderClosedAppPrepareTest,org.fossify.messages.inkpilot.studio.StudioReminderClosedAppVerifyTest'
run_tests recovery package org.fossify.messages.inkpilot.runtime
run_tests app-lock package org.fossify.messages.inkpilot.ui
run_tests prepare class "$smoke#prepareForSyntheticSms"

adb shell run-as "$package" mkdir -p files/models
adb shell -T "run-as $package sh -c 'cat > files/models/active.gguf'" < "$model_path"
adb shell run-as "$package" sha256sum files/models/active.gguf | tee "$evidence/model-sha256.txt"
grep -q '^43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452 ' "$evidence/model-sha256.txt"

# A pinned installed model must not bypass the qualification gate while offline.
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global stay_on_while_plugged_in 3
observe_sms local-loop "$smoke#incomingSmsNeedsStaffWhileModelIsUnqualified" local-loop-ready "$fixture_number" "$fixture_message"
adb exec-out run-as "$package" cat files/device-test-evidence/local-loop.json > "$evidence/local-loop.json"
python3 - "$evidence/local-loop.json" <<'PYCODE'
import json,sys
proof = json.load(open(sys.argv[1]))
assert proof['outcome'] == 'BLOCKED_UNQUALIFIED'
assert proof['modelInvoked'] is False
PYCODE
adb exec-out run-as "$package" cat files/device-test-evidence/local-staff-attention.png > "$evidence/local-staff-attention.png"

# A second instrumentation process proves on-disk replay after process termination.
adb shell am force-stop "$package"
run_tests restart class "$smoke#blockedUnqualifiedStateSurvivesProcessRestart"
run_tests navigation class org.fossify.messages.inkpilot.smoke.HomeNavigationSmokeTest
for tab in sms phone contacts studio calendar; do
  adb exec-out run-as "$package" cat "files/device-test-evidence/tab-$tab.png" > "$evidence/tab-$tab.png"
done

# Mirror launcher lookup: MAIN/LAUNCHER filters do not need the DEFAULT category.
mapfile -t launcher_components < <(adb shell cmd package query-activities --brief --components --query-flags 0 \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$package" | tr -d '\r')
test "${#launcher_components[@]}" -eq 1
launcher_component="${launcher_components[0]}"
[[ "$launcher_component" == "$package/"* ]]
[[ "$launcher_component" =~ ^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$ ]]
printf '%s\n' "$launcher_component" > "$evidence/launcher-component.txt"
# Shell launch also verifies that the discovered enabled component is publicly exported.
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$launcher_component"
observe_sms worker-wakeup org.fossify.messages.inkpilot.smoke.WorkerWakeupSmokeTest worker-ready +12025550124 "$worker_message"
adb exec-out run-as "$package" cat files/device-test-evidence/worker-wakeup.json > "$evidence/worker-wakeup.json"
# Native send, batch inference, and reviewed-scope model smokes remain source fixtures
# for a separately qualified route. Running them here would require enabling RAW QAD.
