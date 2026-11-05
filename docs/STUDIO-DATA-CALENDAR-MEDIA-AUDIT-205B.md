# Studio data, calendar, and media source ledger — 2026-11-05

Pinned tree: `e915096acfa7da10c19538f104f2f4c8aed543ca` (`205b20f6`). The 32
production files listed below were read in full, 2,592 lines at that tree.
`StudioRepository.kt` (163 lines) and the named UI/composer callers were traced
for ownership and call paths; their other functions are covered by separate
audit owners. This ledger is a bounded source review, not whole-repository,
combined-APK, carrier, or OnePlus acceptance.

## Exact full-read file coverage

| Package | Files read completely at pinned tree | Responsibility and caller boundary |
| --- | --- | --- |
| `inkpilot.data` | `LeadQuestionMask.kt`, `StudioArchiveCleanupNotice.kt`, `StudioArchiveExporter.kt`, `StudioContacts.kt`, `StudioCrossThreadAttention.kt`, `StudioDatabase.kt` | Lead mask; export cleanup generation; ZIP table and original serialization; contact identity and reviewed memory; cross-thread staff attention; schema create/upgrade/open and transaction wrapper. Called from lead intake, archive UI, Studio repository, and native inbound/draft flow. |
| `inkpilot.data` | `StudioIdentitySchema.kt`, `StudioProposalSchema.kt`, `StudioProposalStore.kt`, `StudioProviderSync.kt`, `StudioRecords.kt`, `StudioRevisionSchema.kt` | Contact aliases and reviewed memory schema; held proposal state and confirmation; native provider reconciliation; record types; contact revision migration. Proposal confirmation is staff mediated; no production model caller creates proposals on this pinned route. |
| `inkpilot.data` | `StudioStaffAttention.kt`, `StudioStaffEditMigration.kt`, `StudioStaffEditSchema.kt`, `StudioStaffEdits.kt`, `StudioVCardImport.kt` | Attention queue and revision checked handling; held edit preference migration and DB write; previewed vCard import. Called by review UI and repository; attention can represent other thread, reassigned alias, and blocked/review work. |
| `inkpilot.studio` | `CalendarEditor.kt`, `CalendarReminderGate.kt`, `CalendarReminders.kt`, `CalendarWallHours.kt`, `StudioCalendarPanel.kt`, `StudioCalendarSchema.kt`, `StudioCalendarStore.kt`, `StudioFeatureRecords.kt` | Staff scheduling, zone/DST validation, hours, time off, revision checked edit/status, WorkManager worker, notification permission gate, and day view. The panel calls store; worker rechecks current row/revision before posting. |
| `inkpilot.studio` | `StudioAlbumStore.kt`, `StudioAlbumsPanel.kt`, `StudioFeatureSchema.kt`, `StudioImageFiles.kt`, `StudioImageLinkSchema.kt`, `StudioPanelHost.kt`, `StudioPanels.kt` | Album/gallery UI and records; bounded provider import to app private original; link/source identity; async panel and activity result bridge. `StudioHomeActivity` hosts panels and MMS attachment import. |

## Function and storage map

| Entry and caller | Storage boundary | Crash, conflict, and ownership result |
| --- | --- | --- |
| `StudioDatabase.onCreate/onUpgrade/onOpen` via `StudioRepository.database` | Studio SQLite v9, separate from rebuildable SMS cache. Foreign keys on, WAL, additive v1–v8 upgrade; v9 adds held edit field. `onOpen` migrates legacy edit preferences inside a DB transaction, then clears preferences. | Failed SQLite upgrade rolls back. Replaying preference import cannot overwrite an existing DB staff edit. The Studio DB is the source of truth for Studio rows; native SMS/MMS remains the Android provider. |
| `StudioContacts` and `StudioVCardImport` via Contacts UI and repository | Normalized number, UUID contact, alias review, reviewed staff memory and contact revision writes in transactions. | Alias reassignment and cross-thread intake create staff attention; unreviewed alias or memory is excluded from agent memory. The import requires staff review. |
| `StudioProposalStore.propose/confirm/dismiss` via proposal review | Validation and confirmation use DB transaction; confirm rechecks event/revision and applies appointment or draft reply only on explicit staff action. | A stale proposal is declined rather than silently booking; proposal reachability from production model remains absent. |
| `StudioCalendarStore.schedule/edit/setStatus` via `CalendarEditor` and `StudioCalendarPanel` | Transactions protect staff/room overlap, hours, time off, zone, revision and row status. Scheduler enqueue occurs **after** the DB commit. | Worker revalidates revision/status/due and checks notification permission. Process death between commit and enqueue leaves no durable WorkManager request until reconciliation on app reopen. |
| `StudioAlbumStore.addOriginal/saveMmsOriginal/linkOriginal` via Studio import and MMS reference UI | DB transactions protect image row, album membership, MIME/source URI dedupe and FK backed links. Removing membership retains original. | Pinned `StudioImageFiles.copyOriginal` renames the private file before the DB transaction; process death can strand up to 64 MB of private image bytes. MIME, copy-size, and decoded-pixel bounds are checked. |
| `StudioArchiveExporter.export` via Archive UI | A read transaction serializes all Studio tables, then it reads DB-selected private original names, hashes entries and writes the manifest last. | Plaintext archive includes Studio copies of messages, held drafts, agent settings and memory. Missing original aborts. There is no restore/import. Failed provider deletion sets a generic cleanup warning without persisting the sensitive URI. |

## Certain gaps and next gates

| Priority | Pinned evidence | Targeted resolution and exact early warning gate |
| --- | --- | --- |
| P1: imported image orphan | `StudioImageFiles.kt:39-70` creates a temporary copy, finalizes `${UUID}.image` at lines 61–62, then inserts through `StudioAlbumStore.addOriginal`/`saveMmsOriginal` at 63–65; only exception cleanup runs. `StudioArchiveExporter.kt:84-87` enumerates DB filenames, so a process death before the insert leaves an invisible, unexported original. | The isolated fix journals the exact session and UUID filename before copy, and at next Studio initialization removes only previous-session, strictly named, DB-unreferenced private files. A committed row wins over cleanup; uncertain DB ownership leaves file and marker intact. Shell gate invokes preparation and recovery Android test classes in separate instrumentation processes, force-stops the target package and verifies `pidof` absent between them. Fixture proves orphan deletion, committed original preservation, partial cleanup, unknown original retention. It simulates the boundary after rename; a real kill during provider import and integrated startup remains a separate acceptance gate. |
| P1: reminder enqueue gap | `StudioCalendarStore.kt:159-202` commits before `reminders(...)`; `CalendarReminders.kt:21-29` enqueues WorkManager only in that later call. | Integrated `StudioHomeActivity` onCreate reconciliation and Calendar panel reconciliation reduce exposure after reopening, but a due reminder while the app remains stopped can still be missed. Gate: force stop exactly after committed reminder row and before scheduler enqueue, wait beyond due without reopening, assert one notification (or an explicit retry/permission state), then reopen and verify exactly one and no stale revision. This requires a durable DB-to-work handoff or periodic/bootstrap reconciliation design, not merely UI callbacks. |
| P1: SMS attachment ownership | `StudioHomeActivity.kt:229-235,320-329` requests `ACTION_OPEN_DOCUMENT`, catches `takePersistableUriPermission` failure and still saves the provider URI into `ComposerStore`; `ComposerStore.load` restores only URI strings, and `MessageDispatcher` rejects unreadable attachments later. | A transient or provider-revoked grant can leave a saved message with an attachment that cannot be sent after process death. Gate: nonpersistable document provider, choose image, kill/restart, prove private readable copy or immediately show actionable reattach state while preserving text/recipient/SIM, and prove no send from unreadable content. This is separate from Studio album import and not altered by this journal patch. |
| P2: artist and gallery scale | `StudioAlbumStore.kt:16-24` stores artist as unconstrained free text relative to `studio_staff`; `:27-36` paginates images by OFFSET, and `StudioAlbumsPanel.kt:94-99` advances by current page count. | Artist album attribution can drift from staff identity; insertion during paging can duplicate or skip a row. Gate: artist rename/duplicate identity and concurrent import across page boundary, then keyset pagination with a stable order if affected in real usage. |
| Release: restore | `StudioArchiveExporter.kt:18-22,52-115` is explicitly export only, with no importer or restore route. | Create verified restore with table version, hash and reference-original checks before claiming recoverable business records. |

## Current integration delta, separate from pinned source

At local integrated `a52ff79273ee003094d56da9955a1b0905afc46e`:
`StudioPanels` and `StudioHomeActivity` save the pending import scope and reject
the result while locked; `StudioCalendarPanel` and Home invoke reminder
reconciliation; `StudioArchiveScope` and exporter disclosure now identify the
plain Studio text and missing restore; album editor was extracted from the
gallery panel. These are source-level improvements, not a combined device
verdict. At this snapshot `StudioImageFiles` still lacked orphan recovery,
and the SMS document picker still saved a URI after a failed persistable grant.
The isolated image patch must be merged against this newer `StudioPanels`
shape, then rebuilt and tested on the resulting SHA.
