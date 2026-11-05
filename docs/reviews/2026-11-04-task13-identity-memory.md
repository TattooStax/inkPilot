# Task 13 review package

Base: Task 12 fixed commit `161b7624be039bd2fe545fb60c55538e8cdf26c5`.

## Behavior

Studio schema 3 preserves the v1 -> v2 -> v3 migration chain. It copies exact legacy addresses into pending aliases and existing aggregate notes into pending sourced memory. Pending notes remain visible to staff but are excluded from agent memory until staff save/review them. Memory remains one editable aggregate entry per contact; corrections replace that entry and forgetting removes it, including the legacy compatibility text.

US/CA ten-digit and +1 variants produce candidates requiring review; the resolver never merges their records. Staff can explicitly assign an exact alias to a contact. Region-scoped resolution requires its supplied region. Incoming provider messages have no asserted region and use only explicitly reviewed regionless aliases. Original inbound recipients are retained for replies; reviewing identity does not rewrite prior history or send claims. Reassignment invalidates former owners' pending sources.

Staff can edit memory with its source, view review state, forget it, export it through the Android share chooser, and confirm phone identity. Memory and pause save in one transaction. Permission reconciliation runs at startup, activity resume, and READ_CONTACTS AppOps changes. A registration race stops partial registrations and leaves retries possible.

## Validation

- Main Kotlin and Android instrumentation Kotlin compilation passed.
- `:agent-core:test` and `:transport:test` passed.
- Host script regression suite: 10 tests passed.
- File-size ratchet and `git diff --check` passed. Baseline growth records the new identity/provenance interfaces, migration, controls, and regression tests; existing files remain below the hard cap.
- New `StudioContactIdentityTest` covers actual v2 migration, candidate ambiguity, reviewed routing, retained recipient, former-owner invalidation, inbound alias creation, atomic rollback via SQLite abort trigger, correction/export/forget, and synthetic permission observer lifecycle.
- Existing actual v1 migration fixture now expects schema 3, preserving its receipts/settings/claims/parts assertions.

## Pending runtime verification

No APK was installed and no physical contacts, SMS, permissions, or provider data were changed. Android instrumentation tests were compiled but not executed. Actual SQLiteOpenHelper upgrade execution, UI behavior, and grant/revoke callback delivery remain pending emulator verification. Task 12 Android runtime verification also remains pending; this branch is isolated and is not approved for main merge or physical deployment. Full Android gate/ratchet runtime completion is not claimed.
