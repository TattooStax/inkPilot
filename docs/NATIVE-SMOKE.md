# Native LFM3 execution evidence

The production C++ engine was compiled and run on the Linux x86_64 build
host with the official LFM3-1.7B QAD Q4_0 weights. This is real local
inference, not a fake model or an HTTP service. These timings do not
establish Android-phone performance.

- llama.cpp revision: `7071ba378afa96dcd69c41e1ec2b4912364dbee4`
- GGUF revision: `cd0c74c575b5c416f3011b5b4c298077f26ca8c2`
- GGUF SHA-256: `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`
- File size: 1,049,615,680 bytes.

## Android failure reproduced and corrected

On 2026-11-04, the Android device test delivered a real synthetic SMS
through the native provider and loaded the JNI library, but no held
reply was created. Replaying its exact production prompt, studio guide,
customer text, and approved memory on the host reproduced the failure:
`Model response exceeded its token budget`, after 69.3 seconds with two
CPU threads. The model reopened a thinking block despite
`enable_thinking=false` and exhausted all 256 generated tokens.

The rendered template and tokenization were verified: 170 prompt tokens,
one BOS, and identical token vectors between the app's segmented path
and upstream flat tokenization for this fixture. Liquid documents that
disabling thinking can be inconsistent in the [official model card](https://huggingface.co/LiquidAI/LFM3-1.7B).

The correction uses the model's normal open reasoning prefix and the
upstream reasoning-budget sampler. Each reasoning block receives at
most one quarter of the total output budget, capped at 64 tokens, before
being closed with the template's actual end tokens. The sampler can
re-arm for another block, but every generated token still counts toward
the unchanged 256-token total. The unchanged 120-second deadline,
2,048-token context, final-text parser, character limit, and control-text
checks remain enforced. There is no partial-output success or fallback
reply.

The first completed replies exposed a separate role problem: the model
sometimes told a customer to contact the studio even though they were
already texting it. The production instructions now explicitly describe
the studio receptionist replying directly to that customer. They retain
the untrusted-data, supplied-facts-only, and no-false-action constraints.
No customer fixture, memory fact, or studio guide was changed.

## Final production-source regression

The current `inkpilot-inference-smoke` uses the same engine, template,
sampler, and output parser as Android JNI. Its mirrored instructions
were checked against the Kotlin production constant plus the unchanged
device-test studio guide. Three model calls ran sequentially with four
CPU threads, portable CPU kernels, and the production 2,048/256-token,
800-character, 120-second limits.

| Synthetic customer case | Result | Time including model loading |
| --- | --- | --- |
| Exact device consultation fixture and approved 30-minute note | Passed | 31.585 s |
| Small rose tattoo price, no supplied price | Passed | 24.587 s |
| Maya next Friday, no supplied availability | Passed | 44.357 s |

Actual final replies, without editing:

> Sure! We can set up a 30-minute consultation. What date and time work best for you?

> I don't have that information right now. Could you let me know which artist you're interested in?

> Sure! What time were you thinking for next Friday?

All replies passed nonempty final text, UTF-16 length, and internal
control-marker checks. They invented no price or specific availability
and claimed no completed booking. Peak resident memory was 1,146,480 KiB
(approximately 1.09 GiB). Different thread counts and prompts mean the
before/after times are diagnostic observations, not a speed benchmark.
These three synthetic cases do not establish comprehensive response
quality; staff review remains mandatory.

The final-text guard was also tightened to reject official closing
tool/function/parameter markers after a diagnostic run exposed a stray
`</parameter>`. The three captured replies passed the tightened check.
Four JVM regressions cover that concrete leak, acceptance of ordinary
`<3` SMS text, blank output, and UTF-16 limits for supplementary characters.

The corrected native library also cross-compiled and linked for Android
x86_64 using NDK 28.2.13676358 and CMake 3.22.1. Actual APK/JNI execution
is a separate device gate.

## Earlier host evidence

On 2026-11-03, an earlier, different short-prompt smoke completed in
26.32 seconds with four CPU threads and peak resident memory of
1,115,692 KiB. It established that the model and engine could execute,
but did not cover the production device prompt. The regression above
supersedes that narrow test for this failure.

## Reproduction

```sh
scripts/inkpilot-device-model.sh /tmp/inkpilot-model.gguf
cmake -S local-inference/src/main/cpp -B /tmp/inkpilot-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/inkpilot-native --target inkpilot-inference-smoke --parallel 2
/tmp/inkpilot-native/inkpilot-inference-smoke /tmp/inkpilot-model.gguf
```

Android-specific evidence comes from `inkPilot device proof`, including
JNI loading, real incoming SMS, automatic WorkManager wakeup, native UI,
durable restart behavior, and emulator telephony. See its uploaded
artifacts and `DEVICE-ACCEPTANCE.md` for the remaining hardware gate.
