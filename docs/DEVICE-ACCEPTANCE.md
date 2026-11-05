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
The studio-memory editor test preserves unsaved text across resume and
activity recreation, while requiring an explicit Save before records change.
The final batch check forces a real-model context failure, then requires
the next customer's reply to be generated in the same production batch.

The Actions artifact contains instrumentation output, screenshots,
model hash, local-loop evidence, and virtual-send evidence. A missing
model, failed test, crash, or skipped required test fails the gate.

## Model-family qualification gate

`scripts/inkpilot-model-benchmark.sh` uses an existing, digest-qualified
LFM3 text GGUF and the pinned native engine with the production 2,048/256
token, 800-character and 120-second limits. It runs 120 synthetic studio
cases per variant, records P50/P95 generation time and peak host RSS, and
flags booking, price, image, medical, cross-contact, recipient, tool and
truncation failures. The raw synthetic replies stay outside the repository.
The report contains hashes and case IDs for staff adjudication; a mechanical
zero-flag result is never a beta pass by itself.

Run both available text quantizations separately. A missing or unqualified
weight is `UNTESTED`. The 4 GB target remains unmeasured. On the intended
8 GB OnePlus, measure at least 20 consecutive offline turns, queue-to-held
P95, peak RSS, battery and thermal state, and have staff score the replies.
The provisional bar is at least 90% acceptable routine drafts, at most
60 seconds P95 queue-to-held, and zero unsafe actions. Source-only and
host-only results do not satisfy the physical gate. See the dated
`LFM3-MODEL-FAMILY-BENCHMARK-2026-11-04.md` pilot and the qualification
report generated for the exact model hash.

## Physical phone checklist

Use the intended studio phone and a separate receiving phone. For internal
beta, download the `inkPilot-foss-debug` artifact from a successful manual
`inkPilot build` workflow run and install **those exact APK bytes**. Record its
run ID, source SHA, APK SHA-256 and signing certificate in
`RELEASE-EVIDENCE.json`, then retain redacted gate-specific proof tied to that
hash. The independently built unsigned minified release APK is a code-quality
check and cannot stand in for the installed candidate.

**Before any install or role change, compare signing certificates read-only.**
Run `adb shell pm path app.inkpilot.android.debug` on the connected phone. If
that package is installed, use `adb pull` to copy its `base.apk` into a private
local test directory; `apksigner verify --print-certs` on that copy and on the
downloaded candidate must show the same signer certificate SHA-256 digest. If the
installed APK cannot be read and compared, stop before installation.
Also check whether `app.inkpilot.android` is installed and holds the SMS/Phone
roles; the debug package is a separate app and cannot inherit its private data
or roles. Do not install or change a role if the current signer differs, if
important records lack a proven backup/restore, or if role rollback has not
been rehearsed. Never uninstall or clear the existing app to force an upgrade.
A stable internal signing key is needed for repeatable in-place beta upgrades;
otherwise qualify migration and rollback on a disposable device first. Record
the read-only comparison and the decision in redacted physical proof.

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

Phone provides staff-initiated manual dialing with Android role and permission
gates; AI cellular audio is not implemented. Real-call acceptance remains pending.
The calendar is local and does not know Square/Google bookings unless
staff enter them. No automatic booking or price promise is authorized by
a generated reply. Memory is staff-edited, not silently learned.

Model generation can still be incorrect; all replies are held for review.
Weights are an explicit import, not bundled or silently downloaded. The
app disables Android cloud backup; a supported export/restore workflow
is still needed before treating this phone as the sole business archive.
OpenFang integration remains an optional future adapter.

The app has an in-shell thread and composer, but every ingress still needs
a five-tab device check. For each inbox tap, new message, notification,
shortcut, supported share or deep link, draft review, forwarding, contact
detail and settings route, verify the five tabs remain visible and Back
returns to the correct canvas. Any remaining inherited activity that
replaces the shell blocks release.
Open a thread, receive a message while composing, attach an MMS image,
rotate/restart and reject a send; the recipient and unsent text/media must
survive. Staff review must appear inside the relevant customer thread.
The app lock must block all cold-entry routes before message content is
shown. A separate Android permission or call surface is the only intended
system handoff. See `INKPILOT-FULL-CODE-AUDIT-2026-11-04.md`.
