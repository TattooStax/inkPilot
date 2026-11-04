# Task 5 dispatch review package

Base: Task 4 ae56a7fd982a19ffdcc090401b68d9972fbc7710. Isolated branch: task5-typed-dispatch.

## Contract

OutgoingMessage captures thread, exact recipient string, original human body, attachment URI list and subscription ID. MessageDispatcher preflights permission and every attachment before invoking its handoff once. Exceptions after handoff are Unknown, with no automatic retry. Accepted means local queue acceptance only; it makes no delivery claim and its provider key may be absent.

ThreadActivity synchronously commits the original composer before dispatch, including the unconverted human body. Rejected and Unknown leave text and selections intact. Unknown claim IDs are persisted separately. Only Accepted clears the durable composer. Restoring a composer restores attachment selections even if the URI has expired, and preserves its selected subscription; unavailable SIMs fail closed and require explicit selection. Recipient drift fails closed. Durable SAF read grants are attempted when supported. URI identity persists even where a provider does not offer a durable grant; expired grants reject sending rather than silently omitting media.

MMS has no usable acknowledgment from the current transaction library, so MMS handoff is conservatively Unknown and the composer remains. Attachment loading exceptions now propagate; no MMS-to-text fallback is allowed. All MMS attachment URIs are preflighted before the first attachment is handed off.

## Scope and interface concerns

Composer storage is separate SharedPreferences, so this commit adds no Room schema migration and does not modify Task 4's migration. Existing scheduled/direct reply callers may ignore the newly returned outcome; scheduled reconciliation belongs to Task 6. Task 10 should consume the typed dispatcher and composer instead of interpreting acceptance as delivery. The legacy MMS library remains opaque, and this commit does not claim callback reconciliation or durable binary attachment copies. Manual retry remains a user action; there is no automatic retry of Unknown.

## Validation

Synthetic instrumentation tests cover unreadable/expired URI, reopen through a new store instance, denied permission, transport exception, exact subscription/request forwarding and acceptance-only deletion. They never invoke telephony handoff. Unit tests exercise permission denial, exact request/subscription forwarding, unknown outcomes without retry, accepted queue acknowledgment and invalid subscription rejection.

The test source was written first, but the initial red compilation had not finished before implementation began. Therefore no runtime red/green proof is claimed. Focused final Kotlin compilation and host unit-test results are reported with the commit handoff. No physical device was mutated, no message was sent, and no branch was pushed or merged.

Final focused gate: `:app:testFossDebugUnitTest --tests org.fossify.messages.messaging.DispatchOutcomeUnitTest :app:compileFossDebugAndroidTestKotlin --offline --max-workers=2` passed with JDK 17, configured Android SDK and home temporary directory. Four host unit tests passed with zero failures/errors. Instrumentation test sources compile; runtime execution, real process restart/grant revocation, full gate and ratchet remain for integrated emulator review. `git diff --check` passed. Final inspection also corrected the legacy SIM list population to include a single active subscription, ensuring exact-SIM validation works on single-SIM phones. Final log: `/home/shawnlowe/inkPilot-physical-test-20261104/task5-final.log`.
