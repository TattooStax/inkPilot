# Bounded draft receipt provenance

The v10 additive `renderer_policy` column marks every pre-v10 held row
`LEGACY_RAW`. A new bounded renderer stamps `BOUNDED_INTENT_V1`; a staff-confirmed
reply proposal stamps `STAFF_PROPOSAL_V1`. Receipt replay requires the exact
renderer policy and the bounded body must equal one of the current reviewed
fixed templates. A historical RAW body remains visible for staff context but
cannot be adopted as a bounded receipt, even after a new review. The legacy
RAW coordinator retains idempotent replay.

Both the review query and transactional send claim require a qualified policy
and matching kind. An old ON bit or a later model flag change cannot turn an
unstamped row into a sendable draft. A new bounded receipt can replay after
restart because its policy is durable; a same-key race with a historical RAW
row stops `INVALID_RECEIPT` without overwriting history. Staff must explicitly
change the source and regenerate a new revision to create a new held reply.

This migration does not enable the model. The current
`BOUNDED_INTENT_QUALIFIED=false` gate still blocks inference and model-draft
approval. The source tests cover migration, replay and claims; hosted Android
and physical proof must pass before this isolated change is integrated.
