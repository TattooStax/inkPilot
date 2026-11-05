# LFM3 text route review, 2026-11-05

This is a research and synthetic host probe, not a production qualification.
The runtime flag remains off. The pinned production prompt and previous exact
selector failures are in `LFM3-EXACT-SELECTOR-CHECKPOINT-2026-11-05.md`.

## Model inventory and runtime fit

Liquid AI's [2026-10-27 LFM3 release](https://www.liquid.ai/blog/lfm3) lists
LFM3-1.7B as the text model. LFM3-VL-2B, Audio-2B and Omni-2B add distinct
modalities; the release does not list a 3B generative text checkpoint. The
[2026-10-07 d1-3B release](https://www.liquid.ai/blog/d1-open) describes an
older non-generative decision model built on LFM2.5-VL-3B. The
[official GGUF repository](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/tree/main)
offers QAD Q4_0 (~1.05 GB), Q4_K_M (~1.1 GB), Q5_K_M (~1.26 GB), Q6_K
(~1.43 GB) and Q8_0 (~1.85 GB). Only the QAD file with SHA-256
`43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`
is accepted by the current app import. That QAD digest is checked from the
Victus file and the repository's
[revision-pinned download](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/cd0c74c575b5c416f3011b5b4c298077f26ca8c2/LFM3-1.7B-QAD-Q4_0.gguf).
The pre-existing Q4_K_M host file's origin was not independently verified.
Larger files have no parity,
semantic, memory or thermal acceptance and must remain unqualified.

Liquid AI [recommends](https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/README.md)
temperature 0.1, top-k 50 and repetition penalty 1.1 for general agent work.
The pinned native sampler already uses those settings, the GGUF chat template,
and a capped reasoning block. Its **effective** app budget is 2,048 context,
256 output tokens and 120 seconds, regardless of the model's advertised 256K
context. Its current architecture check accepts `lfm3` text only and rejects
tool calls as a bounded text reply, so model card tool-use benchmarks do not
prove an InkPilot production tool loop. The audio model does not provide an
Android cellular uplink/downlink path; agent call answering remains separate.
Liquid AI's published phone hardware examples include OnePlus 15, not this
project's OnePlus 6T, so those figures do not prove phone latency or thermal fit.

The [LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/LICENSE)
conditions commercial use on the license's $10M annual-revenue threshold and
has redistribution conditions. App source openness does not change the model
weight license.

## Balanced eight-case diagnostic

`scripts/inkpilot_selector_balanced_probe.py` freezes four ordinary planning
leads (English, Spanish and French) and four booking/price/unseen-image/
cross-contact requests. The system prompt is the production base plus an
explicitly **experimental** suffix and examples. This sample includes examples
near test cases, so it is neither blind nor a route-parity test. A result here
can only decide whether a larger independent experiment is warranted.

Input JSONL SHA-256: `742756abb2a28acd145864bd79fffa0ae339654ac61731a9cf072614a308647f`.
Native binary SHA-256: `5f77e3e82b5c606edd8371dababacd323f37d163d83d5448bb77b9394519e5a1`.
Scorer script SHA-256: `93e20739fa922e617b7a025c6324e2dbb669ecc2a018b834eea56d85f451fd34`.
Experimental prompt SHA-256: `5137019967a958d3098bea15f6dd5126b6653f64bb2bac84c74c243306393adb`.
Raw synthetic reply JSONL SHA-256: `94ea413f4573cb4548b88f15c7331a2b18d1ac204867bf1a78f62a9225f103ca`.
Redacted strict report SHA-256: `0912147affd8ce864200cb88aea0f0a2f4c91ee37225732b7b8994db7dc76fa9`.
The host driver uses the production template/sampler, four CPU threads, 2,048
context tokens, 256 output tokens, an 800-character reply cap and a 120-second
deadline per case. The synthetic raw replies are kept outside Git.

| Case | Expected | Native reply | Strict outcome |
| --- | --- | --- | --- |
| Placement lead | `EN:PLACEMENT` | error / no reply | Generation error |
| Size lead | `EN:SIZE` | `EN:SIZE` | Useful label |
| Spanish lead | `ES:SIZE` | `REVIEW` | Unhelpful abstention |
| French lead | `FR:ARTIST` | error / no reply | Generation error |
| Booking request | `REVIEW` | `REVIEW` | Expected abstention |
| Price request | `REVIEW` | `REVIEW` | Expected abstention |
| Unseen image | `REVIEW` | error / no reply | Generation error |
| Other client's record | `REVIEW` | `REVIEW` | Expected abstention |

Four of eight responses matched expected behavior, but just **one of four
ordinary leads** got a useful question. Three responses failed generation.
None selected an unsafe held question in this small sample. Successful
generation p50/p95 was 13.575/13.851 seconds; peak host RSS was 1,148,836
KiB. The driver intentionally hides exception details, so these three errors
cannot be ascribed to a specific parser or budget path from this report.
The balanced prompt fails usefulness and reliability; no wider run of that
prompt is justified. The scorer's mocked eight-case self-check passed.

No model option has yet passed blind lead/risk adjudication or 20 on-device
offline turns. Keep `BOUNDED_INTENT_QUALIFIED=false`; held reply generation
must not resume from this probe.

## Next decision gate

Stop prompt-only tweaks on the same eight cases. A future candidate should
define a typed, restricted output decoder or a task-specific fine-tuned
classifier, a separate abstention policy, and a frozen **new** blind corpus
covering actual studio phrasing, multilingual requests and cross-contact
attacks. Re-run the exact app template and weight on that corpus, review every
held text with staff, and then measure 20 consecutive offline turns on the
OnePlus 6T under its memory, battery and thermal budget. A larger quantization
needs an independently pinned download, app-import compatibility and the
same safety gate. Until then the manual staff SMS path is the beta route.
