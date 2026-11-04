# Task 20 review package

Manual Phone implementation: ACTION_DIAL fills a number without calling; opt-in Android Phone role picker, previous-holder reminder and Default apps rollback; explicit TelecomManager.placeCall after the human taps Call; package-targeted existing-dialer fallback when role is absent; call-history and contacts routes. Exported InCallService is protected by BIND_INCALL_SERVICE, never returns a null binding, and retains system ringing. A high-importance call notification opens a full-screen incoming/manual ongoing UI. It tracks all supplied calls, supports answer/decline/end, capability-gated hold/resume, conference merge/swap/join, SIM selection, DTMF and post-dial sequence, mute and supported audio routes.

Caller identity is suppressed while either Android keyguard or inherited app lock is locked. Notifications contain no caller identity at any time. Call activity is secure and excluded from recents. Dial links use the inherited unlock flow before showing the panel. No carrier audio capture, transcription, model answering or AI voice behavior is implemented or claimed.

Official requirements reviewed:
- https://developer.android.com/develop/connectivity/telecom/dialer-app
- https://developer.android.com/reference/android/telecom/InCallService

Emergency numbers use the same Telecom placeCall path, allowing the platform to route to the preloaded emergency dialer. No emergency call was placed. Android handles fallback if service binding fails. The fallback is not an excuse to consider a broken service qualified.

Validation:
- Focused Kotlin compilation of all six phone files against Android 36 public API, JDK17, and small UI/commons shims: passed (deprecation warnings for compatibility APIs). This validates Android signatures, not application integration.
- Executed six caller-presentation mock assertions: normal, emergency strings 911/112 and null all redacted while locked; unlocked/private rendering passed, no Telecom invocation.
- Added read-only PhoneRoleQualificationTest: dial intent resolution, service binding permission/export/UI metadata, locked normal/emergency mock identity. Full instrumentation remains for integration gate.
- File-size baseline increase in AndroidManifest.xml is justified by required exported ACTION_DIAL activity, protected InCallService and internal in-call activity. No UI-logic file exceeds the cap.

Pending before live use: parent integrated build/tests; emulator incoming/ongoing call actions, role refusal/revocation, missing external handlers, background/full-screen notification behavior and permission denial. Physical OnePlus dialer role and calls were deliberately untouched. Real inbound/outbound second-handset validation and reversible role trial still require the controlled gate; this is not a completed physical-device Phone release.
