#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

# Disposable hosted emulator only; keep the first-launch fixture first.
serial=emulator-5554
[[ $(adb -s "$serial" get-state | tr -d '\r') == device ]]
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Shell baseline requires a disposable emulator' >&2; exit 2; }
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
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.StudioArchiveTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.StudioArchiveCleanupNoticeTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.ui.StudioStaffAttentionTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.ui.StudioArchiveHandoffTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.ScheduledVerdictTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.ScheduledQueueTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.ScheduledIdentityLifecycleTest
# Synthetic ingress, calendar, model-contract, and optional-bridge regressions.
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.ShellIngressTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.runtime.CarrierIngressTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.studio.StudioAvailabilityTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.studio.StudioCalendarUiTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.data.StudioProposalTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.ModelQualificationTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.smoke.OneShellAcceptanceTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.inkpilot.phone.PhoneRoleQualificationTest
scripts/run-inkpilot-test-class.sh "$serial" org.fossify.messages.transport.BridgeHistoryTest
