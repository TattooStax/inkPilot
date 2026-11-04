# Task 4: typed message cache migration

Room v12 copies every extant v11 message with `(id,is_mms,is_scheduled)` as
its primary key. The attachment column is copied as opaque text; separate
MMS media tables, drafts and conversation/archive records are untouched.
Missing migration paths now fail closed rather than erase the database.

Legacy recycle rows receive kind code 4 (`LEGACY_UNKNOWN`). They remain
queryable through `getUnresolvedRecycleBinEntries`, and do not join either
provider namespace or any timed/explicit recycle purge. A normal provider
thread refresh imports SMS and MMS separately; it resolves a legacy tombstone
only when both provider queries succeed and exactly one namespace contains
that ID. Denied/null/failed queries, two matches and zero matches retain the
tombstone for review. This reconciliation never deletes provider records.

Origin-aware DAO operations and scheduled/recycle callbacks carry MessageKey.
Legacy scheduled alarm lookup uses `single()` so two scheduled rows sharing
an ID fail closed. Task 6 owns durable dispatch and typed alarm identity.

A local scheduled row already overwritten before v11 cannot be reconstructed
from an absent database/provider record. Provider backfill occurs on normal
thread refresh, when permission and provider availability allow it; migration
itself performs no provider I/O. Unresolved tombstones have a review API;
a dedicated staff review screen is outside this schema change.

Verification: frozen v11 SQL demonstrates the old collision failure; host
SQLite executes the production migration and actual DAO SQL for four-way
identity, opaque media preservation, unknown tombstone quarantine, scoped
deletion and rollback at every migration statement. Private-database Android
fixtures cover Room validation, archived/draft/media preservation, four-way
recycle/restore/delete/restart, provider-resolution truth table and upgrade
failure/retry. Instrumentation execution is reserved for hosted CI; no device
or Telephony mutation was performed for this task.

Small ratchet baseline changes cover the typed DAO wrappers, model schema/diff
fields, receiver import and Task 1 fixture API adaptation. Large files did not
grow; migration and tombstone reconciliation are separate sources.

Review follow-up: resolution now inserts a typed tombstone only if absent,
then keeps `MAX(existing deleted_ts, legacy deleted_ts)` in the same Room
transaction that removes unknown. An old legacy timestamp cannot shorten a
newly recycled message's retention. Both SMS and MMS coexistence/expiry and
restart cases run through the production reconciler in the new private-DB
integration fixture. Host SQL tests cover both older and newer typed dates.

The production reconciler accepts a read-only Cursor query port; its Context
adapter queries the two native Telephony URIs. Synthetic provider integration
covers all 16 present/absent/null/denied combinations, cursor closing, exact
backfilled keys, preserved local scheduled content, unique conversion and
ambiguous tombstone retention. Provider snapshots remain unchanged, and the
port supplies no delete/update capability. Frozen media fixtures now assert
nested production-shaped attachment JSON byte-for-byte before backfill and
after reopening. These instrumentation assertions are compiled locally;
execution remains a hosted-CI requirement.
