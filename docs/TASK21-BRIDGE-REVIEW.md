# Task 21 review package

Base: `1b5fd9d`. Branch: `task21-provider-bridge`.

## Result

Optional companion integration remains outside the default standalone beta.
An explicit `-PinkpilotOptionalBridge=true` build enables pairing and pickup;
existing credentials do not enable a default build. The standalone Settings
pairing entry is hidden. The existing pure `:transport` module remains the
boundary for later adapters, including OpenFang; local messaging remains usable
without remote setup.

Enabled sends require the default SMS role. A durable endpoint/device/source
claim precedes a verified Telephony outbox receipt and radio submission. The
receipt has the recipient's native thread id. Multipart callbacks retain the
first result for each part and reconcile the same provider row to sent/failed
only after the aggregate verdict. Reconciliation happens before ack filing,
including after disconnect; a provider exception retains the completed verdict
for the existing recovery drain. A local one-time recovery trigger uses that
same worker without a network prerequisite, survives disconnect, and backs off
while completed verdicts remain. Settings/boot schedule recovery also re-arms it.

Source tombstones survive acknowledgement, progress cleanup and disconnect.
Replayed work cannot reset progress or resubmit. Source keys encode identity
components separately, avoiding delimiter collisions. Diagnostic rendering
redacts tokens, device secrets and exchange bodies; local SMS failures show a
generic localized message rather than exception text. HTTP endpoint acceptance
now matches Android's localhost/127.0.0.1 allowlist; IPv6 HTTP is refused.

Periodic pickup is labelled delayed. Fifteen minutes is a scheduling interval,
not a latency deadline; Android can defer it further.

## Validation

Passed locally:

- `:transport:test`: 192 tests, including pairing redaction and HTTP policy cases.
- `:app:testFossDebugUnitTest`: 17 host tests.
- Default `:app:compileFossDebugKotlin` and
  `:app:compileFossDebugAndroidTestKotlin`.
- Opt-in versions of both compile tasks with `-PinkpilotOptionalBridge=true`.
- Python source/tool guards: 14 tests.
- `git diff --check`.
- File-size ratchet, with deliberate baseline changes for configuration guards,
  native reconciliation, redaction and regression coverage. Obsolete sender
  documentation was condensed; no cap or exception was relaxed.

`BridgeHistoryTest` uses a synthetic ContentProvider and isolated preferences.
It covers provider/thread projection, failed multipart aggregate, conflicting
duplicate callback, progress reset refusal, source replay after cleanup and
fresh context creation, claim-before-insert crashes, provider insert failure,
reconciliation retry, disconnected recovery worker backoff, and default build
refusal. It never changes roles,
touches device SMS history or calls SmsManager.

Android instrumentation was compiled, not executed: only physical ADB devices
were available and the local disposable AVD was unavailable. Real provider and
carrier behavior remain an acceptance gate. No phone pairing, role change,
live send, push or main merge occurred.

## Deliberate limits

Submission is at-most-once, not exactly-once. A crash after claim but before
radio submission can leave an unsent claim or pending outbox row; a crash after
submission without callbacks leaves a pending row with unknown outcome.
Neither is automatically retried or falsely marked sent. New source ids must
represent new owner-authorized work, not automatic retries of ambiguous work.

Claims persist for app-data lifetime. Clearing app data removes replay
protection; endpoints must not reuse old source ids after re-pairing. Historical
bridge sends predating source claims cannot be reconstructed into receipts.
A user-deleted provider row is not recreated by a late callback.

To keep the integration off, omit the Gradle opt-in property. This is the
default for both debug and release builds.
