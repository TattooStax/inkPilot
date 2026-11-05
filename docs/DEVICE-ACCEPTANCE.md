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
3. Injects a synthetic incoming SMS through the emulator modem and verifies
   its native provider row. With the pinned model installed and radios off,
   the current unqualified policy must report `BLOCKED_UNQUALIFIED`, make no
   model call, and create no held or outgoing reply.
4. Opens the staff-attention surface and captures a screenshot. Force-stops
   and reopens the app to verify the block and attention survive restart;
   captures all five tabs.
5. Injects a second SMS for another synthetic contact and verifies normal
   inbox receipt without waking model drafting. Native send, multipart
   callback, and reviewed-scope fixtures remain separate tests for a future
   qualified route; this blocked loop is not carrier-delivery proof.

The app-lock test verifies that a locked notification
entry opens the inherited unlock screen without rendering studio data.
The studio-memory editor test preserves unsaved text across resume and
activity recreation, while requiring an explicit Save before records change.

The Actions artifact contains instrumentation output, screenshots,
model hash and blocked-loop evidence. A missing
model, failed test, crash, or skipped required test fails the gate.

## Model-family qualification gate

`scripts/inkpilot-model-benchmark.sh` uses an existing, digest-qualified
LFM3 text GGUF and the pinned native engine with the production 2,048/256
token, 800-character and 120-second limits. Its fixed corpus has 120 synthetic
studio cases; a surviving variant must complete at least 100, with all 120
planned here. A variant that already makes an unsafe claim can be stopped and
reported as a disqualified partial sample. The harness records P50/P95
generation time and peak host RSS, and
flags booking, price, image, medical, cross-contact, recipient, tool and
truncation failures. The raw synthetic replies stay outside the repository.
The report contains hashes and case IDs for staff adjudication; mechanical
scoring and independent automated review do not replace staff scoring. A
mechanical zero-flag result is never a beta pass by itself. Current evidence
is 120/120 QAD cases and 24/120 Q4_K_M cases (two per category, stopped after
unsafe booking claims); neither variant is qualified.

Run both available text quantizations separately. A missing or unqualified
weight is `UNTESTED`. The 4 GB target remains unmeasured. On the intended
8 GB OnePlus, measure at least 20 consecutive offline turns, queue-to-held
P95, peak RSS, battery and thermal state, and have staff score the replies.
The provisional bar is at least 90% acceptable routine drafts, at most
60 seconds P95 queue-to-held, and zero unsafe actions. Source-only and
host-only results do not satisfy the physical gate. See the dated
`LFM3-MODEL-FAMILY-BENCHMARK-2026-11-04.md` pilot and the qualification
report generated for the exact model hash.

The later fixed-vocabulary selector has its own [exact-route checkpoint](LFM3-EXACT-SELECTOR-CHECKPOINT-2026-11-05.md):
QAD matched 0/24 expected labels, Q4_K_M matched 5/13 before stopping, and a
safer prompt returned REVIEW for every one of 24 cases, including all ordinary
leads. None qualifies an enabled production drafting route.

## Physical phone checklist

Use the intended studio phone and a separate receiving phone. For a fresh
side-by-side canonical candidate, configure a protected
`inkpilot-beta-signing` environment with required reviewers, restricted
candidate branches and the five signing secrets listed in
`RELEASE-PRIVACY.md`. Commit the public certificate digest file on that
branch before dispatch. Dispatch `inkPilot signed canonical beta build`, then
download its `inkPilot-foss-release-beta` artifact. It must be signed by the
durable key and have package `app.inkpilot.android`. The existing manual
`inkPilot build` workflow remains an option for a compatible debug install;
its artifact has package `app.inkpilot.android.debug`. Install **the exact
downloaded APK bytes** and record `candidate_kind` (`release` or `debug`),
run ID, source SHA, APK SHA-256 and signing certificate in
`RELEASE-EVIDENCE.json`, then retain redacted gate-specific proof tied to that
hash. An independently built unsigned minified release APK is only a source
check and cannot stand in for the installed candidate.

**Before any install or role change, compare packages and signers read-only.**
Run `adb shell pm path` for both `app.inkpilot.android.debug` and
`app.inkpilot.android`. For an in-place upgrade, pull the installed
`base.apk` into a private local test directory and compare its certificate
SHA-256 with the exact downloaded candidate using `apksigner verify
--print-certs`; a mismatch blocks that upgrade. A canonical candidate can
install side by side only if its package is absent. Its separate app-private
data does not migrate from the installed debug app. Android's native SMS/MMS
provider is shared, and switching the default SMS role diverts incoming
traffic. Record the original SMS and Dialer role holders, plan and rehearse
rollback, and verify the native provider and old app after the test. Do not
clear or uninstall the existing app to force an upgrade. A stable internal
signing key enables later upgrades of the canonical app. Side-by-side carrier
proof does not pass migration or backup/restore; qualify those separately
before customer use. Record the read-only comparison and decision in redacted
physical proof.

Read-only preflight on 2026-11-05 found a OnePlus A6013 on Android 11 with
`app.inkpilot.android.debug` installed and holding the SMS role; OnePlus Dialer
held the Phone role. The installed APK certificate SHA-256 began `f4152e41`;
the available local debug APK and its keystore began `91c34bcb`. That local
APK cannot upgrade the installed app in place. The installed manifest has
`allowBackup=false`; `run-as` works, but no verified cross-signer private-data
and native-provider restore exists. No role, app, or customer data was changed.
A successful push-build artifact from run `41942630288` at source `85440d0`
was also inspected read-only: APK SHA-256 `0cf672ba8f0f6fc02b1c5eac6e888dc3bb5776255395cf6b846a79a2e94340e4`,
signer SHA-256 `0a1d508fc6ffba41d81fdac8020aa0641f24e324468bb59bd1ea96239a59ef56`.
It differs from the installed signer and is not a manual internal-beta candidate.
The canonical `app.inkpilot.android` package was absent in the read-only
preflight. A signed canonical candidate with stable key has not been built or
verified. The exact future candidate needs its own signer/package comparison;
no physical install or role change had occurred at that preflight.

A later controlled test installed a disposable preview package alongside the
existing debug app, briefly selected it as the default SMS app, and sent one
synthetic SMS from its in-shell composer. Android's native sent-message provider
recorded one matching outgoing row; recipient delivery was not confirmed. The
previous InkPilot debug app was restored as SMS role holder, OnePlus Dialer
remained the Phone role holder, and the disposable preview was uninstalled.
This exploratory test is not an exact signed-candidate or carrier-matrix pass.

A newer side-by-side preview on the OnePlus passed 21 targeted UI/data
instrumentation methods without taking the SMS or Phone role. It exercised
reminder, recovery, MMS outcome, side-card, calendar route, route-state and
fake-dial behavior. The five bottom-tab titles, Assistant side-card route
and Settings alias also stayed within the shell. One of four first Phone
taps about 1 second after a legacy Main-alias launch missed, even with Home
focus and correct coordinates; three of three taps after the SMS UI was
visible worked. No input-listener cause was established, and normal launcher
entry was not tested. These checks did not transmit a carrier message or
place/answer a call. The separate
[2026-11-05 checkpoint](BETA-EVIDENCE-2026-11-05.md) tracks current source
and hosted gates; this checklist remains mandatory for the exact installed
candidate.

- Install the APK, choose inkPilot as default SMS, and grant permissions.
  Drafting remains disabled while the bounded LFM3 route is unqualified.
- Receive a plain SMS; confirm one native message, one matching contact,
  and a usable manual reply path. Confirm no model draft or automatic send.
- Correct and forget contact memory; verify old content cannot be restored
  by a queued Save or appear in another client's view.
- Reply manually with an explicit business SIM. Verify sender number and
  complete text at the receiving phone.
- Test a multipart reply and a reply containing non-ASCII characters.
  Verify one native outgoing message and accurate status.
- Kill/restart before a manual send and during an ambiguous attempt. Verify
  saved composer content and that nothing is automatically sent again.
  Check the recipient before any manual resend.
- Exercise airplane mode, no credit/service, permission/role removal,
  and dual-SIM selection. Errors must not claim delivery.
- Send/receive MMS in the native conversation. Agent image understanding
  is not implemented, so do not interpret a text reply as image analysis.
- After independent selector qualification, run the separate 20-turn local
  model and held-draft acceptance matrix before enabling drafting.
- Check contact isolation, album original preservation and calendar
  artist/room conflicts using the actual day-to-day workflow.

## Current scope limits

Phone provides staff-initiated manual dialing with Android role and permission
gates; AI cellular audio is not implemented. Real-call acceptance remains pending.
The calendar is local and does not know Square/Google bookings unless
staff enter them. No automatic booking or price promise is authorized by
a generated reply. Memory is staff-edited, not silently learned.

Model generation remains off in the current production policy because its
replies were not qualified. A future enabled route must hold every reply for review.
Weights are an explicit import, not bundled or silently downloaded. The
app disables Android cloud backup. Shell Settings offers a user-initiated,
plaintext Studio ZIP export with a versioned table/image manifest and SHA-256
entries; it includes all Studio database tables, including agent settings,
guide and copies of message text, plus retained originals without album
membership. It excludes native SMS/MMS provider history and model weights; there is no
supported import/restore yet. A lock during export stops it; if a document
provider cannot delete an incomplete file, a generic cleanup warning persists
until staff acknowledge it after unlock. Remove the file from its chosen location. Do not treat this phone
or the ZIP as a complete business archive or use the export to justify a
destructive signer change.
A tested provider plus Studio export/restore and role rollback remains a beta
acceptance gate.
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
