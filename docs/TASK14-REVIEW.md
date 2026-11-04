# Task 14 review package

Base: 184a3da (Task 4 typed MessageKey plus Task 13 Studio v3).

Studio now upgrades v1/v2/v3 to v4 through additive, OpenHelper-transactional
image link and original source tables. Existing contacts, memories, albums,
images and memberships remain untouched. MMS provenance stores origin and row
ID separately; SMS/scheduled origins are rejected. Links use canonical contact
IDs, opaque project IDs and existing staff/appointment foreign keys.

`StudioImageFiles.importMmsOriginal(uri, link)` copies into private files once
per source part URI, retaining MIME, dimensions, byte count and PRIVATE_COPY
ownership. The private copy owns future reads: no persistable grant is required.
Repeated imports link the existing record before trying to open the source URI.
A failed copy/database link removes its new file and rolls back its records.
Membership removal leaves both provenance and the original intact.

Contact reference navigation uses StudioRoute.ImageLinks, persisted through the
existing route codec and rendered in the host. StudioPanels exposes the same
paged gallery for a thread ID, and its internal MMS import hook accepts a typed
StudioImageLink. Task 9's thread media controls must call this hook and open the
typed route when integrated; this branch does not add a second thread UI.

Validation:
- App Kotlin and Android test Kotlin compilation passed with JDK 17 / SDK.
- App host unit test suite passed (final task14-final.log: BUILD SUCCESSFUL).
- Ratchet and git diff whitespace checks passed.
- Lint executed and failed solely on inherited ManualDialActions.kt:42
  MissingPermission (placeCall wrapped by runCatching); no Task 14 lint errors.
  This is not suppressed or baselined by this change.
- Synthetic SQLite schema, metadata insert and typed-origin constraints passed.
- StudioImageLinksTest covers v3 migration preserving old records/membership,
  copy dedup after source deletion/grant revocation, restart and removal,
  contact/project/staff/appointment/source provenance, 105-image pagination,
  and rollback on invalid contact/origin.
- Instrumentation tests are compiled, not executed: local emulator fails before
  boot. Hosted CI must run StudioImageLinksTest and StudioAlbumStorageTest.
- No live MMS/customer data, phone role changes, deployment, merge or push.

Ratchet growth is deliberate for the v4 migration, link/source store, private-copy
import, typed route and contact gallery action, with synthetic regression tests.
The base also omitted Task 13 baseline entries; its inherited baseline growth
is included when refreshing the ratchet, with no legacy-cap relaxation.
