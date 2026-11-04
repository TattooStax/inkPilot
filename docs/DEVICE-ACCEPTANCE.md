# Running and accepting inkPilot

The build and emulator workflows are executable evidence gates, not a
substitute for carrier testing on the intended phone. Use synthetic
customer data until the physical checklist is complete.

## Automated device gate

`inkPilot device proof` installs the real app and instrumentation APK on
Android API 35 x86_64. It:

1. Exercises production SQLite storage, including concurrent/replayed
   writes, pause/revision barriers, multipart receipts, gallery originals,
   and staff/shared-room conflicts. Actual native inbox rows also verify
   intake recovery, invalid-row progress and storage-failure retention.
2. Installs the checksum-pinned official LFM3-1.7B QAD Q4_0 GGUF, then
   disables Wi-Fi and mobile data. The APK has no inference-server path.
3. Injects a synthetic incoming SMS through the emulator modem, checks its
   native provider row, and uses the production local runtime to produce
   a held draft. No fake success model is used in this loop.
4. Opens the actual draft review UI and captures a screenshot.
5. Force-stops/reopens the application, checks the draft survives and
   cannot be recreated by replay, and captures all five tabs.
6. Rejects an invalid SIM without consuming the held draft. If the
   emulator has an active virtual SIM, exercises approval/send, provider
   persistence, and duplicate-approval rejection. Requires real terminal
   callback receipts and matching native provider type/status; an unknown
   or still-pending attempt fails this proof. A genuine emulator rejection
   is accepted only with its failure callback and matching failed row.
   This does not claim real-world recipient delivery.

An additional incoming SMS to a separate synthetic contact must wake
WorkManager and create its held reply without the test invoking the
runtime directly. The app-lock test verifies that a locked notification
entry opens the inherited unlock screen without rendering studio data.
The final batch check forces a real-model context failure, then requires
the next customer's reply to be generated in the same production batch.

The Actions artifact contains instrumentation output, screenshots,
model hash, local-loop evidence, and virtual-send evidence. A missing
model, failed test, crash, or skipped required test fails the gate.

## Physical phone checklist

Use the intended studio phone and a separate receiving phone:

- Install the APK, choose inkPilot as default SMS, grant permissions,
  import the tested model, add current guidance, and enable drafting.
- Receive a plain SMS; confirm one native message, one matching contact,
  and one held reply. Verify no message leaves before staff approval.
- Correct memory or pause during inference; confirm the stale result
  cannot become a fresh reviewable draft.
- Review/edit a reply, explicitly select the business SIM, and approve.
  Verify the sender number and complete text at the receiving phone.
- Test a multipart reply and a reply containing non-ASCII characters.
  Verify one native outgoing message and accurate status.
- Kill/restart the app before approval and during a send. Confirm the
  held draft persists and an ambiguous attempt is never automatically sent
  again. Check the recipient before any manual resend.
- Exercise airplane mode, no credit/service, permission/role removal,
  and dual-SIM selection. Errors must not claim delivery.
- Send/receive MMS in the native conversation. Agent image understanding
  is not implemented, so do not interpret a text reply as image analysis.
- Measure first-token/total draft time, peak RAM, battery use and thermal
  behavior during repeated drafts on the target 4 GB/8 GB device.
- Check contact isolation, album original preservation and calendar
  artist/room conflicts using the actual day-to-day workflow.

## Current scope limits

Phone opens Android's human dialer; AI cellular audio is not implemented.
The calendar is local and does not know Square/Google bookings unless
staff enter them. No automatic booking or price promise is authorized by
a generated reply. Memory is staff-edited, not silently learned.

Model generation can still be incorrect; all replies are held for review.
Weights are an explicit import, not bundled or silently downloaded. The
app disables Android cloud backup; a supported export/restore workflow
is still needed before treating this phone as the sole business archive.
OpenFang integration remains an optional future adapter.

The inherited conversation/settings UI can still show Commons' modified-
package warning. The new studio home avoids that incorrect branding
warning while reusing the real lock manager and unlock activity. Replacing
that inherited notice remains UI cleanup for broader distribution.
