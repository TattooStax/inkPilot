# InkPilot agent core

This pure Kotlin/JVM module implements one customer-service workflow: read an
authoritative inbound message and contact memory, ask a local model for a bounded
reply, and persist a **held draft** with a checked receipt. It has no SMS sending,
booking, approval, network, Android, database, or OpenFang implementation.

## Current status

The coordinator and its unit tests are implemented. The Android app now wires
a SQLite host, local inference adapter, and held-draft review screen; their
integration and carrier behavior require separate device qualification. The
module tests use explicit in-memory contract fakes, so they alone do not
establish durability, model quality, or a working end-to-end beta. OpenFang
integration remains optional future work.

The standalone Android host supplies the three constructor dependencies:

| Port | Host responsibility |
| --- | --- |
| `HeldDraftStore` | Read the canonical inbound/contact state and transactionally persist held drafts and replay receipts. |
| `ContactMemoryReader` | Read staff-approved memory scoped to the source contact. |
| `LocalDraftModel` | Run the qualified local model once with hard inference budgets and cooperative cancellation. |

An optional future OpenFang integration should call the same host-owned workflow
and repository. It must not create a separate customer database or bypass draft
review. The module has no required OpenFang dependency.

## Workflow and limits

1. Validate the host trigger against the authoritative event, thread, contact,
   revision, and pause state. The recipient comes exclusively from this source.
2. Reuse an existing receipt for the same event and draft kind, if available.
3. Retrieve only this contact's approved memory; reject cross-contact data.
4. Make one inference call with text data and instructions. The model receives no
   recipient selector, identifiers, tools, or ability to commit effects.
5. Reject empty or oversized output, then atomically hold the proposed reply if
   the source is still current and staff have not paused the thread.
6. Report success only after checking the returned committed receipt against the
   source identity, revision, and, for a new draft, the exact proposed body.

Default limits are 4,000 inbound characters, 12 memory entries totaling 8,000
characters, and an 800-character reply. Character counts use UTF-16 code units,
not SMS segments. Excess input fails closed instead of silently discarding facts.
The adapter must additionally impose model-specific token limits, a deadline,
and device resource limits. The synchronous coordinator cannot interrupt an
uncooperative inference implementation. Call it away from the Android UI thread.

Model text and memories remain untrusted. Prompt wording is guidance, not an
injection-proof guarantee. Replies can still contain bad advice or unsupported
facts; they remain held for staff review. This first workflow intentionally has
no autonomous sending path.

## Required repository guarantees

`holdIfCurrent` must implement the contract in `HeldDraftStore.kt` inside one
durable transaction. A unique `(eventId, kind)` key must survive process restarts
and draft consumption/deletion. Concurrent callers may both perform inference,
but only one draft can be committed. An in-process lock or a coordinator cache
does not satisfy this requirement.

The `holdIfCurrent` transaction must recheck event/thread/contact/recipient
binding, revision, and pause before inserting or returning a replay receipt. Source edits, memory
changes, recipient changes, explicit cancellation, and pause/resume changes must
advance the host revision. This invalidates already running inference. A
cooperative cancellation callback reduces wasted work; the persisted revision is
the protection against cancellation racing a commit. Cancellation after commit
does not erase a held draft.

`ALREADY_HELD` reports an existing receipt; it is not approval to send and does
not establish that a draft remains suitable after later customer/staff changes.
The initial `findHeldDraft` lookup is historical and does not atomically attest
to current pause/revision state. This workflow does not regenerate drafts for the
same inbound event after revision changes; explicit regeneration is future work.
Any future approval/send workflow must independently recheck current state.
If storage throws after an attempted write or returns an invalid receipt, the
coordinator returns `PersistenceUnconfirmed`. The host should reconcile/retry
using the same key, not invent a new event ID. Model/read errors return a failed
stage without claiming a draft exists or exposing customer data in error text.

## Verification

Run `./gradlew :agent-core:test` with the repository's configured toolchain.
Tests cover receipt replay, competing insertion, uncertain commit recovery,
stale revisions, staff pause, cancellation, changed recipient routing, contact
scope isolation, bad receipts, model failure, and input/output limits.

The host has real SQLite integration tests and a held-draft review UI. Before
use with studio customers, qualify the local model, recipient/SIM routing and
carrier receipts on the intended Android phone and complete the physical
acceptance checklist. No model-family support is claimed by this module alone.
