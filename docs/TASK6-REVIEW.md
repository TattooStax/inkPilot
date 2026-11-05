# Task 6 review package

Base: `68a387e39dc8de30e54c93d8fa551e40fe13894a` (corrected Task 5).
Branch: `task6-durable-verdict`. No push, merge, phone sends, SMS role changes, or emulator launch.

Scheduled rows remain durable queued items. A SQLite journal atomically claims the
exact scheduled MessageKey before any carrier handoff. Interrupted DISPATCHING
claims are never automatically retried. Each SMS recipient/part and separate MMS
attachment receives explicit callback correlation. Only all-part confirmation
permits removal of the scheduled row; failed/unknown items remain recoverable.
Expiry cleans only CONFIRMED rows, covering death after receipt commit but before
row cleanup. Time alone cannot delete an unresolved send.

Alarm data URIs contain full Long IDs and scheduled SMS/MMS origin. Cancellation
uses the same identity and FLAG_NO_CREATE. Receiver lookup additionally checks the
thread. Legacy raw-ID alarms fail closed; normal rescheduling registers typed
alarms for queued rows. Durable claims prevent duplicate radio handoffs.

Studio multipart receipts continue reconciling UNKNOWN claims after reopen.
Failures with pending or successful parts yield UNKNOWN; all rejected parts yield
FAILED, and all confirmed parts yield SENT. UNKNOWN cannot be claimed again.
Existing first-receipt-wins handling of contradictory duplicate part receipts is
preserved. Failed MMS resend callbacks preserve the original MMS.

Validation evidence under `/home/shawnlowe/inkPilot-physical-test-20261104/`:

- `task6-red.log`: five host regression checks fail against frozen base sources.
- `task6-host-green.log`: five checks pass, including production journal DDL in host
  SQLite, close/reopen, and unique claim protection.
- `task6-final.log`: Kotlin and Android-test compilation plus three JVM tests of
  shared production aggregation and typed alarm identity pass.
- `ScheduledVerdictTest`: Android journal reopen, late mixed outcomes, typed URI
  parsing, actual PendingIntent collision isolation, and callback correlation.
- `StudioSendPartsTest`: first failure then late success remains UNKNOWN after
  reopen and cannot be claimed again.

Instrumentation is compiled but unexecuted under the supplied restrictions.
No carrier or hardware behavior is claimed. Initial compilation hit the existing
full `/tmp`; reruns use supplied TMPDIR plus JAVA_TOOL_OPTIONS java.io.tmpdir for
Room's SQLite native loader.

Ratchet growth is limited to callback parameter plumbing, async receiver lifetime,
typed alarm identity, and regression assertions. Dispatch, journal, and reducer
responsibilities live in small separate files. Oversized legacy files shrink.

Review focus: durable claim before every handoff; part indices cover the whole
scheduled dispatch; uncertain outcomes never become retryable; only all-part
confirmation cleans the exact MessageKey; raw legacy alarms cannot choose a kind.

## Independent review follow-up

The first review found four legacy UI gaps. The follow-up persists queued Room
rows before attempting an alarm, removes all due-time hiding, displays journal
status in scheduled bubbles, and routes send-now through durable dispatch without
unconditional deletion. Invalid SIM/media preflight leaves the row queued.
Claimed sends expose status/check-conversation guidance and explicit deletion;
edit/send-now actions are offered only for queued rows. Storage independently
rejects edits once claimed, preserving evidence even if the editor was opened
before an alarm fired. Failed alarm posting retains the editor and the exact
queued row identity for a deliberate correction.

Queued edits and dispatch snapshot+claim now serialize in the journal transaction.
Dispatch reloads the latest exact Room row while holding that guard, then commits
the claim before radio handoff. An old alarm snapshot cannot send stale edited
content or overwrite a claimed record. The scheduled UI no longer writes the row
again after alarm posting, avoiding resurrection after a fast success callback.

`task6-review-red.log` demonstrates the legacy paths against the first Task6
commit. `task6-review-host.log` has six passing host checks. The focused Gradle
verification adds seven passing JVM tests and compiles ScheduledQueueTest, whose
production queue seam injects only alarm posting: it asserts Room persistence
before a thrown post, retained overdue rows on rejection, UNKNOWN edit refusal,
and latest-snapshot claiming. Instrumentation execution remains deferred.

## Thread migration and cross-kind edit follow-up

Typed scheduled identity is now authoritative across temporary-to-real thread
moves. The receiver resolves its immutable MessageKey against the current Room
row; an old THREAD_ID extra cannot consume an otherwise valid alarm. Dispatch
also reloads the current row without rejecting a migrated thread ID. Untyped
legacy alarms continue to fail closed. This uses the globally unique typed row
identity rather than mutable conversation metadata to authorize resolution.

Cross-kind replacement now uses MessagesDao.replaceScheduled, one Room
@Transaction encompassing insert of the new identity and deletion of the old.
The separate journal edit guard still serializes it with dispatch claims, but is
no longer relied on to roll back changes in the Room database.

ScheduledIdentityLifecycleTest adds the exact production migration/old-alarm
resolution flow and verifies both SMS-to-MMS and MMS-to-SMS replacement. A
controlled SQLite trigger aborts the deletion after the insertion; closing and
reopening the disposable Room database must recover only the original row.
Successful replacement and reopen must recover only the edit. No test registers
an alarm or invokes a carrier handoff.

Host regression checks now total eight, including SQL trigger interruption and
reopen in both kind directions using the production DAO deletion query.
`task6-rereview-red.log` reproduces the missing guards against 2998031;
`task6-rereview-host.log` passes. `task6-rereview-verified.log` records focused
production/Android-test compilation and the seven scheduled JVM tests.
Instrumentation execution remains deferred to the hosted emulator handoff.
