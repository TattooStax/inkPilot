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
# Keep independent class evidence even when an assertion fails. Stop if setup or
# the disposable emulator becomes unusable; later verdicts would be misleading.
failed_classes=()
run_class() {
  local status=0
  scripts/run-inkpilot-test-class.sh "$serial" "$1" || status=$?
  case "$status" in
    0) return ;;
    1)
      failed_classes+=("$1")
      if [[ $(adb -s "$serial" get-state 2>/dev/null | tr -d '\r') != device ]]; then
        echo 'Emulator became unavailable after a failing class' >&2
        exit 2
      fi
      ;;
    *) echo 'Stopped after test environment failure; later classes not run' >&2; exit "$status" ;;
  esac
}
run_class org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest
run_class org.fossify.messages.inkpilot.smoke.ShellBaselineTest
# Model-free, disposable provider/UI regressions run after the clean-install baseline.
run_class org.fossify.messages.inkpilot.smoke.InShellThreadTest
run_class org.fossify.messages.inkpilot.smoke.ThreadReviewRegressionTest
run_class org.fossify.messages.inkpilot.smoke.FocusedReviewRouteTest
run_class org.fossify.messages.inkpilot.smoke.InShellComposerTest
run_class org.fossify.messages.inkpilot.smoke.ComposerClaimRecoveryTest
run_class org.fossify.messages.inkpilot.studio.StudioImageLinksTest
run_class org.fossify.messages.inkpilot.studio.StudioImageImportUiTest
run_class org.fossify.messages.inkpilot.data.StudioArchiveTest
run_class org.fossify.messages.inkpilot.data.StudioArchiveCleanupNoticeTest
run_class org.fossify.messages.inkpilot.ui.StudioStaffAttentionTest
run_class org.fossify.messages.inkpilot.ui.StudioArchiveHandoffTest
run_class org.fossify.messages.messaging.SmsPartJournalTest
run_class org.fossify.messages.messaging.GroupSmsRefusalTest
run_class org.fossify.messages.inkpilot.data.ScheduledVerdictTest
run_class org.fossify.messages.inkpilot.data.ScheduledQueueTest
run_class org.fossify.messages.inkpilot.data.ScheduledIdentityLifecycleTest
run_class org.fossify.messages.inkpilot.data.StudioCrossThreadAttentionTest
run_class org.fossify.messages.inkpilot.data.StudioContactIdentityTest
run_class org.fossify.messages.inkpilot.ui.ContactForgetUiTest
run_class org.fossify.messages.inkpilot.data.StudioVCardImportTest
run_class org.fossify.messages.inkpilot.data.ThreadDraftLookupTest
# Synthetic ingress, calendar, model-contract, and optional-bridge regressions.
run_class org.fossify.messages.inkpilot.smoke.ShellIngressTest
run_class org.fossify.messages.inkpilot.ui.NotificationEntryRouteTest
run_class org.fossify.messages.inkpilot.ui.VCardShellUiTest
run_class org.fossify.messages.messaging.ComposerSharedMediaTest
run_class org.fossify.messages.inkpilot.smoke.ComposerShareFailureIngressTest
run_class org.fossify.messages.inkpilot.runtime.CarrierIngressTest
run_class org.fossify.messages.inkpilot.runtime.DirectReplyPreservationTest
run_class org.fossify.messages.inkpilot.runtime.SmsAdmissionLedgerTest
run_class org.fossify.messages.inkpilot.runtime.SmsIntakeRecoveryTest
run_class org.fossify.messages.inkpilot.smoke.MmsComposerOutcomeTest
run_class org.fossify.messages.inkpilot.studio.StudioAvailabilityTest
run_class org.fossify.messages.inkpilot.studio.StudioCalendarUiTest
run_class org.fossify.messages.inkpilot.studio.StudioReminderGateTest
run_class org.fossify.messages.inkpilot.data.StudioProposalTest
run_class org.fossify.messages.inkpilot.data.StudioStaffEditTest
run_class org.fossify.messages.inkpilot.data.StudioLeadIntakeTest
run_class org.fossify.messages.inkpilot.data.StudioLeadIntakeMigrationTest
run_class org.fossify.messages.inkpilot.staged.LeadIntakeReviewUiTest
run_class org.fossify.messages.inkpilot.smoke.ModelQualificationTest
run_class org.fossify.messages.inkpilot.smoke.OneShellAcceptanceTest
run_class org.fossify.messages.inkpilot.phone.PhoneRoleQualificationTest
run_class org.fossify.messages.inkpilot.phone.PhoneCallAlertsTest
run_class org.fossify.messages.inkpilot.phone.CallNotificationActionsTest
run_class org.fossify.messages.inkpilot.phone.ManualDialUiTest
run_class org.fossify.messages.inkpilot.phone.ManualCallUiTest
run_class org.fossify.messages.transport.BridgeHistoryTest
if ((${#failed_classes[@]})); then
  printf 'FAIL: %d test class(es); per-class redacted verdicts are in app/build/test-class-evidence/\n' "${#failed_classes[@]}" >&2
  printf '  %s\n' "${failed_classes[@]}" >&2
  exit 1
fi
