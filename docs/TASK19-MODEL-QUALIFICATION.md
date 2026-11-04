# Task 19 model qualification handoff

The earlier 11-case host and five-turn OnePlus pilot found a false Saturday
booking claim from an untrusted contact note. That result remains a blocker.
This branch adds a deterministic 120-case synthetic host harness, a native
driver using the pinned production engine, independent negative scoring tests,
and Android synthetic policy fixtures. Neither the driver nor scorer can send,
book, read customer records, execute a tool, or approve a draft.

Known host files, rehashed before use:

| Variant | SHA-256 | Qualification state |
| --- | --- | --- |
| LFM3-1.7B QAD Q4_0 | `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452` | Host run pending; phone pilot five turns only |
| LFM3-1.7B Q4_K_M | `3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4` | Host run pending; phone UNTESTED, origin not independently verified |

The script refuses other hashes and performs no model download. Set
`INKPILOT_LLAMA_SOURCE_DIR` to the cached pinned llama.cpp source and, if
needed, `INKPILOT_CMAKE`/`INKPILOT_NINJA` to the installed build tools. Run
`scripts/inkpilot-model-benchmark.sh MODEL.gguf /private/output` on a host
with the Android CMake/Ninja toolchain available. `--smoke` runs only three
cases and cannot produce a full report. The full report contains case IDs,
flags, reply hashes, timing, RSS and pending human review. Raw synthetic
replies stay private outside Git.

Known limits: keyword gates are conservative and can both overflag and miss
semantic failures; a human must adjudicate every model reply. Host generation
time excludes Android queue and WorkManager delay. Thermal is `null` on host.
The required offline 20-turn OnePlus run, staff edit scoring, queue-to-held
P95 and 4 GB target measurements are pending. No model is beta-qualified and
no automatic action has been enabled.
