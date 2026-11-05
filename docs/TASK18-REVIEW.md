# Task 18 review: held local action proposals

## Scope

The host can validate and persist three scoped proposal types: appointment, client memory correction, and reply draft. JSON is flat, versioned, bounded, and rejects unknown or repeated fields. A request must match the inbound event's contact and thread scope. The model runtime still does not dispatch tools: syntax alone did not qualify a local model for safe calls.

The v6 `action_proposals` ledger stores the source revision, canonical payload, staff reviewer, state, and effect receipt. Staff opens **Action proposals** from the assistant drawer, with held items above history. Confirmation is one database transaction that rechecks the latest inbound source, ownership, contact pause, revision, reviewer, payload, and artist/room/time-off/studio-zone availability. An approved memory change records its reviewed source. An approved reply becomes a held draft bound to the original recipient; it still needs the existing explicit SIM and checked SMS review. The proposal path never sends SMS or places a call.

Replay returns the same held ID or approved receipt. Dismissed and stale proposals remain tombstoned. Only one proposal reply can create a sendable held draft for an inbound event; checked claims reject a second claim across draft kinds. Staff confirmation of another proposal after an availability change returns a conflict without booking.

## Checks

- `ToolProposalTest`: strict names/version/scope, malformed and duplicate JSON, forged recipient, bounds, prompt injection as inert text.
- `StudioProposalTest` Android source: valid booking receipt and exactly-once restart, no claim before separate review, stale inbound, staff identity, memory revision, room/time-off/studio-zone/staff-hours recheck, duplicate replies, and v5-to-v6 migration.
- `scripts/test_studio_proposals_v6.py`: frozen v5 DDL upgrade, uniqueness/foreign keys/state checks, and rollback after each migration statement.
- Gradle gate: focused `agent-core:test`, `compileFossDebugKotlin`, and `compileFossDebugAndroidTestKotlin`.
- File-size ratchet and `git diff --check`.

## Qualification boundary

Android instrumentation tests are compiled but have not been run on the live SIM phone in this task. No native tool-call dispatch is enabled. A model can produce a proposal only after a later protocol qualification gate; staff actions remain the sole effect path.
