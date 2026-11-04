# Task 7 carrier ingress and receiver integrity — source review package

Base: `31872f7` (Task 6 initial branch), to be rebased onto Task 6 review fixes. The local beta requires the default SMS role. No physical phone, carrier send, role, or model was touched here.

## Production contracts

- `SmsReceiver` acts only on `SMS_DELIVER` while inkPilot holds the SMS role. It commits a native SMS row and requires both positive provider and thread IDs **before** cache, notification, bridge, or agent intake. A failed insert and keyword/number/unknown-contact filters create an idempotent private SQLite record that staff can review and copy from Agent. No filtered record is drafted or auto-sent.
- Notification direct reply uses a unique per-message data URI, re-reads the native inbox row and exact address/thread/subscription before any send, and refuses an inactive or changed subscription. Its text is synchronously held before validation/handoff and never replayed automatically after an uncertain result. `RESPOND_VIA_MESSAGE` is parsed by scheme and recipient, persists a held reply, and refuses ambiguous dual SIM selection.
- MMS callback is nonexported. `PushReceiver` remains the protected carrier WAP push entry point. The library's callback is an explicit same-package broadcast selected by task affinity (`mmslib-1.0.1` local bytecode, `BroadcastUtils.addClassName`); it accepts only `content://mms/{id}` or `content://mms/inbox/{id}`. Native MMS intake is scheduled as unique persisted WorkManager work and reads the provider before rendering notification/cache.
- Permissions-only direct-send callbacks use a different immutable PendingIntent identity for every recipient/attempt/part. Unknown exception details are not displayed in a toast. The optional bridge remains off in the local beta; Task 21 owns its provider-backed history and replay gate.

## Tests and proof limits

- `CarrierIngressPolicyTest`: positive native identity, exact SIM IDs rather than indices, canonical MMS URI and reply binding, response URI rejection. Initial focused test attempt did not demonstrate a red state because production policy source was written while the build was running; no red/green claim.
- `CarrierIngressTest` (emulator only): failed native insert via the actual receiver continuation (without provider mutation), private filtered-journal idempotence and exact cleanup, native SMS source binding/cross-thread rejection with owned provider row, nonexported MMS callback and rejected file/media URI, distinct multipart callback tokens, held direct reply store reopen. This class compiles; it has not run on Android yet.
- Focused host tests and main/AndroidTest Kotlin compilation passed in `.superpowers/task7/` logs. Ratchet and integrated gate are reported after final rebase below.
- A real carrier MMS download and direct reply on the OnePlus are **untested**. The library starts its own thread after broadcast completion before calling the app callback; persisted work closes the app's subsequent processing lifetime, but cannot guarantee that the library callback itself survives a process kill before it is invoked. The native provider remains the source of record for recovery.

## Reviewer focus

1. Android Telephony provider's actual `sub_id` and thread behavior on API 30/35; verify `CarrierIngressTest` and real carrier direct reply with two SIMs before claiming a device beta.
2. `MmsReceiver` explicit same-package callback under nonexported manifest and WorkManager recovery from process death; validate one genuine received MMS with an attachment.
3. Failure between direct-reply SmsManager handoff and queued acknowledgment remains held for staff review; no automatic replay. Confirm no unrelated manual composer is overwritten.
