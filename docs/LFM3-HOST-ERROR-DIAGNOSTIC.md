# Host-only LFM3 error categories

The native qualification driver now emits `error_category` as one of
`NONE`, `OUTPUT_TOKEN_BUDGET`, `OUTPUT_BYTE_BUDGET`, `DEADLINE`,
`CANCELLED`, `CONTEXT_BUDGET`, `BOUNDED_REPLY_PARSE`, `MODEL_LOAD`,
`TEMPLATE`, `RESOURCE`, or `OTHER`. It compares exact engine failure
messages internally and never serializes an exception message. Unknown
failures map to `OTHER`.

The exact-selector scorer validates the closed vocabulary and copies only
the category into `nativeErrorCategory` and aggregate
`nativeErrorCategories` in its redacted report. Old raw observations with no
field are labelled `LEGACY_UNCLASSIFIED`. This does not retrospectively
diagnose the seven errors in the frozen 36-case report. That fixture,
candidate, and report were not rerun or modified.

`python3 -m unittest scripts/test_lfm3_error_categories.py
scripts/test_inkpilot_exact_selector_benchmark.py` exercises each category
with synthetic exceptions and checks that unknown text cannot enter a
category or a report. The host driver still requires a pinned llama.cpp
source and model for a full build. This diagnostic does not qualify a model
or enable Android drafting; the production gate remains off.
