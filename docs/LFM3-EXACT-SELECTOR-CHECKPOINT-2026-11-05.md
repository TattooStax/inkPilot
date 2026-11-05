# Exact bounded LFM3 selector checkpoint, 2026-11-05

Pinned source: `e915096a` (tree `205b20f6`); production
`AgentRuntime.BOUNDED_INTENT_QUALIFIED` is `false`. This report concerns the
closed `EN|ES|FR:PLACEMENT|SIZE|STYLE|REFERENCE|ARTIST|DATE` renderer, not the
older free-text draft prompt or an agent tool loop. The model cannot send an
SMS in this host experiment.

`scripts/inkpilot_exact_selector_benchmark.py` extracts the Kotlin instruction
and the exact dynamic language/missing-question suffix. It freezes 120
synthetic cases across ordinary leads, multiple missing details, Spanish,
French, unsupported booking/price/availability/image/medical requests, and
customer, note and cross-contact injection. Its full corpus SHA-256 is
`bd57170ee4488a66ce55a851769a865576b5ccd6b82e01fb9f873d8e7e8097f4`;
base instruction SHA-256 is
`a5bcce613e1e2ea1055572d991a12928ca0ed63b4ecb223c7799b07852f123e2`.
The native driver uses the app's inference engine, LFM3 template and sampler,
with a 2,048 token context, 256 output token reserve, 120-second deadline and
four CPU threads. The benchmark scorer parses raw model output with the same
exact-label rule as production; later trimming of the rendered customer
template does not make a spaced model label valid. Its seven corpus/scoring
tests pass. Raw replies remain in a private host directory.
The checked-in wrapper also completed a one-case native QAD smoke on Victus
(exit 0, 0/1 expected exact label); that smoke is only a CLI wiring check.

| Exact production prompt, fixed first cases | Expected outputs | Exact labels | Exact REVIEW | Errors | Median / p95 successful generation |
| --- | ---: | ---: | ---: | ---: | ---: |
| QAD Q4_0, SHA-256 `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`, 24/120 | 0/24 | 0 | 0 | 1 | 9.246 / 9.578 s |
| Q4_K_M, SHA-256 `3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4`, stopped at 13/120 | 5/13 (3 lead labels, 2 risk REVIEW) | 3 | 4 | 2 | 23.658 / 25.165 s |

These counts were re-scored from the pinned raw host observations after the
raw-label fidelity correction. Redacted strict-report SHA-256 values are
`05ec8d3aae560da71b3ebc8d2ae6302247bdd3ff4b31b278236d0eb63449dbbc`
(QAD) and
`d8774a8a33bae24a56871d978b9fbc1f72981ac0f93d7f40d7fde6e230b36240`
(Q4_K_M). The 13-case Q4 report is an interrupted partial sample, not a
24-case or full-family result.

The QAD outputs often used JSON or spaced labels even when the semantic field
looked plausible. It also selected missing questions for risk requests. Simply
parsing JSON or forcing a valid label could turn those risk selections into
held customer-visible questions. The Q4 sample is too small and includes
unhelpful REVIEW decisions on ordinary leads. These exact-prompt results are
not a useful or safe beta qualification. The 120-case corpus is fixed, but a
full run on an already failing prompt would not change that decision.

## Focused prompt candidate, stopped

The separate `inkpilot_exact_selector_prompt_probe.py` appends a safety-first,
one-line format instruction to the production base. It is **not** the installed
prompt. Candidate instruction SHA-256 is
`29a16e40051742508099e2e5701e2bf498a62c19b457c89cb3434d20125e13aa`;
the first-24 input SHA-256 is
`bc4bff8d2f029aed46980960951ba30f64493e859b971141852cfe5fe99c87ff`.
We checked that independently generated local input has exactly that digest.
The same pinned QAD file and native binary (SHA-256
`5f77e3e82b5c606edd8371dababacd323f37d163d83d5448bb77b9394519e5a1`)
ran the fixed first 24 cases on the Victus CPU. The redacted report SHA-256 is
`aea07eac27a2dc371e79951b919fb8b7f5caa7fc4a3449dff1218cad044cfad9`.

All **24/24** outputs were exactly `REVIEW`: 8/8 risk cases got the expected
abstention, while **16/16 ordinary leads got no useful question**. No
generation errors occurred; successful generation p50/p95 was 12.060/12.311
seconds, peak host RSS 1,148,380 KiB. The renderer would reject every REVIEW,
leaving staff to reply manually. This candidate fails usefulness immediately,
so it was stopped at 24 rather than tuned or run over all 120. The result is a
prompt sensitivity observation, not evidence that a new prompt safely handles
arbitrary risky customer messages. No blind held-out set or physical-device
qualification was performed for this candidate.

The synthetic oracle is only a benchmark aid. Human staff usefulness scoring,
blind adjudication, source-grounded context, production route parity, and 20
offline turns on the intended OnePlus with latency and thermal data remain
unmeasured. Q4_K_M's pre-existing host file origin has not been independently
verified. Keep the production flag off while those gates are open.
