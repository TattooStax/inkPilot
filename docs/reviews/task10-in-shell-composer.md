# Task 10: composer and held review

Manual compose and thread replies now render in the inkPilot shell. Recipient, original text, URI list, and exact subscription are committed using ComposerStore on edits and before handoff. The picker requests persistable document grants; inaccessible attachments are rejected without text-only fallback. A bounded thread footer retains the thread list and global navigation.

Held replies render in the thread footer; staff edits persist separately from model output. Staff must explicitly choose a business SIM and press Approve & send. AgentRuntime owns freshness checks and the durable send claim. Draft-list review and conversation buttons route within the shell; the old review wrapper also routes into the shell.

Manual handoff records an unknown marker before crossing the transport seam. Process death during handoff retains the original draft and disables resending; MessageDispatcher and ComposerStore reject a repeat unknown claim. No automatic retry or model send was added.

Validation: focused app Kotlin and Android-test compilation plus app JVM unit tests. InShellComposerTest includes synthetic unreadable-MMS retention, unknown no-repeat/no-downgrade, and shell/rotation editing coverage. No instrumented suite was executed: the authorized disposable Victus emulator crashes before Android. No physical phone sends or role changes were performed. Hosted emulator execution remains required.

Integration: Task9 cherry-picked as 1359b1e from 4bf6f8f onto 1b5fd9d. Only ratchet conflict was resolved; Task10 leaves paging/loading/status paths unchanged so the independent Task9 fixes can integrate first. Ratchet growth in StudioHomeActivity is host composer/picker wiring; new composer and review logic stay in separate small files.

Limitations to review: MMS transport returns Unknown rather than a verified acceptance; its retained composer stays locked to avoid duplicate sends. Attachment presentation is filename/URI based with removal; carrier compression and attachment rendering remain the existing MMS path. Runtime UI/carrier acceptance is not claimed by compile evidence.

## Independent review follow-up

- Every Compose route now owns a random negative composer identity, encoded with its route. A different recipient/body/media route uses a separate durable slot; rotation restores that same slot. Thread replies retain their positive provider thread identity.
- ComposerStore creates one stable attempt identity for a draft. Dispatch holds a process mutex and OS file lock across compare and durable intent marker creation; the carrier handoff runs after releasing that short storage lock. Attempt markers are fsynced before handoff; accepted attempts retain tombstones, and Unknown retains both the draft and pending marker. A concurrent or recreated host cannot replay the same accepted/unknown attempt. Rejected preflight removes the attempt so staff can correct and retry. These private claim files contain only marker bytes, not message text.
- Thread-held and direct-ID lookups query the target before applying their bounded limit. An idempotent thread/state/date index is created on database open; the direct ID uses the existing primary key.
- Asynchronous preparation returns Result, including SIM/provider/persistence exceptions. The UI re-enables Send only if no durable attempt was reached. The same retry policy is exercised by the synthetic failure fixture.
- New compiled instrumentation covers distinct recipient/media routes across recreation, two synchronized hosts with one Accepted handoff plus accepted replay after reopening, preclaim failure versus unknown retry policy, and an old held draft behind 110 newer records from other threads. Existing route/restart fixtures now preserve/assert explicit attempt and route identity.
