# Frozen LFM3 typed selector result — 2026-11-05

The [preregistered 36-case diagnostic](LFM3-TYPED-HOLDOUT-2026-11-05.md)
completed on the Victus CPU host. It used synthetic messages only and did not
read phone data, take Android roles, draft, call or send SMS. The production
model gate remained off.

| Outcome | Count |
| --- | ---: |
| Ordinary planning: useful field | **0/18** |
| Ordinary planning: unhelpful `REVIEW` | 12/18 |
| Ordinary planning: generation error | 6/18 |
| Risk: correct `REVIEW` | **17/18** |
| Risk: generation error | 1/18 |
| Risk: allowed field that would hold a question | 0/18 |
| Total exact expected | 17/36 |

Successful generation p50/p95 was 9.523/9.837 seconds. Process peak RSS was
1,148,900 KiB (about 1.10 GiB); total host run time was 427.39 seconds. All
29 successful generations returned exact raw format after the native engine,
but every success was `REVIEW`. The seven errors were caught by the host
driver; their exception categories were intentionally not exposed, so the
report does not attribute them to a parser, context, model or time budget.

Reproduction identity:

- Model QAD GGUF SHA-256: `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`.
- Native driver SHA-256: `5f77e3e82b5c606edd8371dababacd323f37d163d83d5448bb77b9394519e5a1`.
- Frozen fixture SHA-256: `3a8136519a690144354111face6fbc51df319292fc675277e4bab9425b812f1f`.
- Independent candidate instruction SHA-256: `3245f1c343a6a92c24bddaecbb659751458434c881f1965b19be603401cf0204`.
- Exact native input SHA-256: `09265aa8f3c8906acd6f8b7e4513b49215dc3ebc6568361cd13102039f063755`.
- Redacted report SHA-256: `3aa7a569a6d46991b93493da007d2905c33b8b0e042a1d2de5d02b0db89bf5f5`.
- Private raw synthetic output SHA-256: `ffe0f4f670e232b64acfb286d022e6cdfa7c59287f1f96a6387c044c7fa1134a`.

The report and raw synthetic output live in a private Victus test directory,
outside Git. The driver reused the production 2,048-token context, 256-token
output, four CPU threads, 120-second per-case deadline, chat template and
sampler. The host did not execute Kotlin repository or renderer code; its
scorer mirrored the strict typed contract, including native adapter trim.

**Decision:** no-go. The frozen threshold required at least 15/18 useful
ordinary selections, at least 17/18 risk reviews, zero risky held fields and
zero generation errors. This run has zero useful ordinary selections and
seven errors. Do not wire the diagnostic into production or enable drafting.
Future work needs a different selection mechanism and independent staff
adjudication; repeating prompt tweaks against this holdout would overfit it.
