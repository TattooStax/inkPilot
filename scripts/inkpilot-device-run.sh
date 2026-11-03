#!/usr/bin/env bash
set -euo pipefail

model_path="${1:?Usage: inkpilot-device-run.sh verified-model.gguf}"
package=app.inkpilot.android.debug
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
smoke=org.fossify.messages.inkpilot.smoke.LocalLoopSmokeTest
evidence=app/build/device-test-evidence
fixture_number=+12025550123
fixture_message='Can I book a consultation? inkPilot device proof.'
mkdir -p "$evidence"

collect() {
  adb logcat -d -t 2000 > "$evidence/logcat.txt" 2>&1 || true
  adb shell dumpsys package "$package" > "$evidence/package.txt" 2>&1 || true
}
trap collect EXIT

run_tests() {
  local name="$1" selector="$2" value="$3"
  timeout 600 adb shell "am instrument -w -r -e $selector '$value' -e fixtureMessage '$fixture_message' $runner" \
    | tee "$evidence/$name.txt"
  python3 scripts/inkpilot-device-result.py "$evidence/$name.txt"
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

run_tests sqlite package org.fossify.messages.inkpilot.data
run_tests studio package org.fossify.messages.inkpilot.studio
run_tests prepare class "$smoke#prepareForSyntheticSms"

adb shell run-as "$package" mkdir -p files/models
adb shell -T "run-as $package sh -c 'cat > files/models/active.gguf'" < "$model_path"
adb shell run-as "$package" sha256sum files/models/active.gguf | tee "$evidence/model-sha256.txt"
grep -q '^43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452 ' "$evidence/model-sha256.txt"

# Inference must work after model installation with network connections disabled.
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global stay_on_while_plugged_in 3
adb emu sms send "$fixture_number" "$fixture_message"
run_tests local-loop class "$smoke#incomingSmsCreatesLocalHeldDraft"
adb exec-out run-as "$package" cat files/device-test-evidence/local-draft-review.png > "$evidence/local-draft-review.png"
adb exec-out run-as "$package" cat files/device-test-evidence/local-loop.json > "$evidence/local-loop.json"

# A second instrumentation process proves on-disk replay after process termination.
adb shell am force-stop "$package"
run_tests restart class "$smoke#heldDraftSurvivesProcessRestart"
run_tests navigation class org.fossify.messages.inkpilot.smoke.HomeNavigationSmokeTest
for tab in sms phone contacts studio calendar; do
  adb exec-out run-as "$package" cat "files/device-test-evidence/tab-$tab.png" > "$evidence/tab-$tab.png"
done
run_tests native-send class org.fossify.messages.inkpilot.smoke.NativeSendSmokeTest
adb exec-out run-as "$package" cat files/device-test-evidence/native-send.json > "$evidence/native-send.json"
