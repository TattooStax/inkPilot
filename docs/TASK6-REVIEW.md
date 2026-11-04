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
