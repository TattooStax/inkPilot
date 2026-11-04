#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

# Disposable hosted emulator only; keep the first-launch fixture first.
serial=emulator-5554
package=app.inkpilot.android.debug
apks=(app/build/outputs/apk/foss/debug/*.apk)
test "${#apks[@]}" -eq 1
adb -s "$serial" install -r "${apks[0]}"
adb -s "$serial" shell cmd role add-role-holder --user 0 android.app.role.SMS "$package"
for permission in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE POST_NOTIFICATIONS; do
  adb -s "$serial" shell pm grant "$package" "android.permission.$permission"
done
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.ShellBaselineTest
# Model-free, disposable provider/UI regressions run after the clean-install baseline.
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.InShellThreadTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.ThreadReviewRegressionTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.InShellComposerTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.studio.StudioImageLinksTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.studio.StudioImageImportUiTest
