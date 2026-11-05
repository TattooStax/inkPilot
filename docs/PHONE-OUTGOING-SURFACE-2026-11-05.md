# Outgoing Phone surface diagnostic — 2026-11-05

The v8 OnePlus preview took the temporary default Phone role and `CALL_PHONE`
permission. A staff-tapped outgoing call changed Android telephony state from
idle to offhook, then was ended and the original Phone role restored. During
offhook, focus remained `StudioHomeActivity` and the UI showed the cached
Manual Phone canvas without End call or Return to call. This proves the
request reached telephony, not that InkPilot's `InCallService` bound or that
its notification was presented. No incoming-call result is inferred.

The source explains a local UI gap: `AndroidDialActions.place` only invokes
`TelecomManager.placeCall`; `StudioInCallService.onCallAdded` updates a
notification without opening `ManualCallActivity`. `onBringToForeground` only
opens it when Telecom calls that callback. The Phone canvas creates its Return
to call button only if `StudioInCallService.current.activeCalls` is nonempty
at initial render. `StudioCanvasState` caches the view, so it does not add
the button when a call starts later.

This isolated candidate opens InkPilot's manual call controls after a
staff-initiated, successfully submitted *nonemergency* Telecom request. The
separate call Activity shows “Opening call controls…” while Telecom binds,
then a truthful unavailable message if no tracked call appears within eight
seconds. The Phone canvas keeps a Call controls action while InkPilot holds
the Phone role; it checks the live service when tapped, so a cached canvas can
return to a call that started later and reports an unavailable state when no
call is tracked. It does not claim an answered or connected call. Emergency calls
continue through `TelecomManager.placeCall` and leave the preloaded system
call UI unobscured. Android's [`TelephonyManager.isEmergencyNumber`](https://developer.android.com/reference/android/telephony/TelephonyManager#isEmergencyNumber(java.lang.String))
decides on API 29+; unavailable classification or older API means system
handoff. Android's [default Phone app guide](https://developer.android.com/develop/connectivity/telecom/dialer-app)
documents the preloaded dialer's emergency role.

Synthetic UI tests use a fake dial boundary and never request a role or place
a carrier call. They assert that denied role/permission, failed request and
system handoff do not open local controls; an accepted ordinary request does.
A cached Phone canvas checks current service state each time Call controls is
tapped, and does not open an empty call surface when there is no active call.
A separate Activity test checks that an outgoing launch does not immediately
report “Call ended” before the asynchronous service binding.

Before accepting this candidate, run the exact source through build/Detekt,
focused instrumentation, and the physical outgoing matrix. During an active
call, record the actual default role holder, `dumpsys telecom` InCallService
binding and call state, InkPilot service/activity lifecycle, notification
posting, focused Activity, visible End call/mute/route controls, and rollback.
Do not include a real phone number or caller identity in shared logs. If
Telecom never binds `StudioInCallService`, opening a waiting Activity alone
does not fix call control; diagnose role qualification or OEM fallback.
