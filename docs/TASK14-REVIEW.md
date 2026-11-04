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
StudioImageLink. Each MMS image now has Save to Client References, which passes
its typed key, thread ID and original Attachment through MessagePanel to the
host. Staff explicitly select a canonical client; successful import invalidates
relevant cached galleries and opens the linked route while still on that thread.
Async completion preserves navigation if staff have moved elsewhere.

Document picker results return to the StudioAlbumsPanel instance that launched
them. Scoped gallery imports retain the contact/thread context and refresh that
same view. Contact-only gallery imports need no thread; thread-only imports ask
staff to select a client. These staff-selected files carry NULL source-message
provenance, with no fabricated MMS key. MMS imports still require typed MMS
origin. A unique expression index keeps nullable provenance links idempotent.
Client References album matching respects the case-insensitive album identity.

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
- StudioImageImportUiTest compiles: scoped picker result refreshes its owning
  gallery and keeps contact/thread links; MMS image action carries original
  Attachment/typed key/thread and is absent for scheduled MMS.
- The source test also covers differently cased Client References album names.
- No live MMS/customer data or physical-phone changes. An isolated Task9 merge
  supports this UI integration; apply only the separate review fix commit after
  integrating Tasks 9 and 14 in main. No push.

Ratchet growth is deliberate for the v4 migration, link/source store, private-copy
import, typed route and contact gallery action, with synthetic regression tests.
The base also omitted Task 13 baseline entries; its inherited baseline growth
is included when refreshing the ratchet, with no legacy-cap relaxation.
