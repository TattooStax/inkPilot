# Local draft policy experiment, 2026-11-05

This branch stages a closed vocabulary for staff-held lead questions. It does
not qualify LFM3, enable a live draft route, authorize a tool, or send SMS.
`AgentRuntime.BOUNDED_INTENT_QUALIFIED` remains `false`.

## Evidence and limits

- Pinned QAD Q4_0, SHA-256 `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`:
  the original native 120-case host run had 119 replies, one generation error,
  and 44 mechanically unsafe texts in the revised semantic report. Neither
  raw free-text prompt nor the narrow booking regex is sufficient.
- Offline replay of its private `task19-results/qad-full/raw-replies.jsonl`:
  **0/119 successful replies** matched the staged renderer's exact
  `EN|ES|FR:PLACEMENT|SIZE|STYLE|REFERENCE|ARTIST|DATE` grammar. This only
  shows that the old prompt has zero usefulness through the new renderer;
  it is not a new native model run or a safe-draft qualification.
- A separate seven-project selector experiment selected `ASK_DETAILS` on
  all seven. Its output grammar and prompt differ from this renderer, its
  route was a synthetic Python fixture, and staff usefulness and human
  adjudication remain pending. It cannot be carried over as a result for
  this code.
- A hand-built held-out set of 18 action, booking, price, image, medical,
  and tool-like raw replies plus two benign free-text replies must all stop
  before persistence in the core orchestration test. These are regression
  checks for output confinement, not a blind estimate of model accuracy.

## Policy staged in this branch

A host-issued `ReviewedLeadIntake` must bind event, revision, contact and
thread, language, and a nonempty set of reviewed missing question fields.
The model may select only one exact allowed label. Rendering replaces the
label with a fixed localized question; no customer text, contact memory,
studio guide, model prose, verified price, calendar data, or image description
is interpolated into the visible reply. A missing scope stops before model
execution. Invalid labels stop before persistence. The existing source
revision check, staff pause, atomic held write and staff send approval still
apply. The app's current evidence reader issues no intake scope. A feature-flag
flip would stop new generation attempts without an existing receipt before
model execution. Previously held raw drafts still return `ALREADY_HELD` and
remain visible for staff review until staff dismisses them or source revision
changes; the new renderer does not retroactively certify those bodies.

The host-issued scope is a code contract only: there is no durable staff
attestation or issuer yet. The existing `VerifiedDraftFact` types are unused
by this renderer; their nonblank source IDs are not proof of verified prices
or appointments. No claims may be derived from them.

The model still needs a dedicated native evaluation with this exact dynamic
prompt, an implemented durable staff-reviewed scope issuer and Kotlin route
parity, human adjudication for correctness and usefulness, and at least 20
on-device offline turns with latency/thermal measurements. Test booking,
prices, availability, unseen images, medical requests, note/customer
injection, cross-contact data, and multilingual messages. Keep action claims
out of fixed templates until independently verified records and explicit
host actions exist. Re-review older held drafts manually; this path screens
only newly generated candidates. No beta percentage should be inferred from
mechanical zero visible claims after rejecting nearly every candidate.
