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

## Hosted RAW-path finding

An offline hosted QAD smoke run on the integrated RAW route persisted the reply
“Sure! We can set up a 30-minute consultation. What date and time work best for
you?” The narrow `BookingClaimGate` missed “we can set up.” This branch adds a
conservative scheduling-arrangement pattern, and a core regression requires
that exact reply to stop before persistence. Production records
`UNSAFE_CONTENT` for staff attention. This only closes that observed phrase
class; the raw model remains unqualified and other unsupported claims may
escape a regex screen. The same hosted run also timed out when the staff UI
tried to find the selected held reply below the viewport. That UI focus defect
is separate from whether the unsafe body was durably saved. The revised smoke
records a blocked outcome with durable attention or a held outcome with its
own UI visibility proof; neither path calls a blocked model reply a successful
draft. A fresh native run of this code is still pending.

## Policy staged in this branch

A host-issued `ReviewedLeadIntake` must bind event, revision, contact and
thread, language, and a nonempty set of reviewed missing question fields.
The model may select only one exact allowed label. Rendering replaces the
label with a fixed localized question; no customer text, contact memory,
studio guide, model prose, verified price, calendar data, or image description
is interpolated into the visible reply. A missing scope stops before model
execution. Invalid labels stop before persistence. The existing source
revision check, staff pause, atomic held write and staff send approval still
apply. Studio DB v8 now stores a staff UI review for one current event, with
revision, contact, thread, reply language, and reviewed missing fields. Save,
correction, and clearing bump the contact revision atomically so an in-flight
old question cannot be held. Reads require every recorded scope field to
match the current source. The Drafts side card exposes setup, correction,
and clearing. The local UI action is recorded as `STAFF_UI` with a timestamp;
there is no individual staff account identity or external attestation.

The feature flag remains false, so reviewed scope does not change the live
raw-draft route. Previously held raw drafts still return `ALREADY_HELD` and
remain visible for staff review until staff dismisses them or source revision
changes; the new renderer does not retroactively certify those bodies. The
existing `VerifiedDraftFact` types remain unused by this renderer; their
nonblank source IDs are not proof of verified prices or appointments. No
claims may be derived from them.

The model still needs a dedicated native evaluation with this exact dynamic
prompt, route parity against independently reviewed cases, human adjudication for correctness and usefulness, and at least 20
on-device offline turns with latency/thermal measurements. Test booking,
prices, availability, unseen images, medical requests, note/customer
injection, cross-contact data, and multilingual messages. Keep action claims
out of fixed templates until independently verified records and explicit
host actions exist. Re-review older held drafts manually; this path screens
only newly generated candidates. No beta percentage should be inferred from
mechanical zero visible claims after rejecting nearly every candidate.

Current production prompt context is one latest inbound body plus reviewed contact memory;
prior messages in the thread are not included. Multi-message lead detail selection
and repeated-question usefulness remain unmeasured.
