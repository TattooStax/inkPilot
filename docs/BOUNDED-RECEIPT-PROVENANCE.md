# Bounded draft receipt provenance

Historical `held_drafts` rows store source identity, revision and body, but no
renderer policy. A current lead review cannot prove an earlier RAW body was
produced by the bounded renderer. The core therefore leaves existing receipts
in storage for staff context but returns `INVALID_RECEIPT` for a replay through
the bounded renderer. If the reviewed lead scope is missing it stops earlier
as `UNSAFE_CONTENT`. A competing insert is also refused. The legacy RAW
coordinator's idempotent replay behavior remains unchanged.

This intentionally also refuses replay of a newly created bounded receipt:
the current schema cannot distinguish it from historical RAW text. Before
turning the presently disabled bounded model route on, add and migrate a
durable renderer/policy version on held rows, validate that version at receipt
read and send claim, and test restart/race replay. This patch does not enable
model inference, change SMS sending or hide the old row from staff.
