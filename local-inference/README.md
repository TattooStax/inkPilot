# Local inference

`LlamaDraftModel(File, InferenceLimits())` implements the agent core's
text-only model port using llama.cpp directly through JNI. It loads an
app-private LFM3 GGUF, formats the model's embedded Jinja chat template,
runs CPU inference, extracts final reply text, and frees the model,
context, sampler and batch before returning. No server, cloud inference,
API key or network request is involved in generation.

The host imports weights to private storage (currently
`files/models/active.gguf`) and calls inference off the UI thread. Calls
are serialized across all adapter instances. Staff still review every
draft; model output never chooses a customer or performs an action.

## Pinned implementation

- llama.cpp commit: `7071ba378afa96dcd69c41e1ec2b4912364dbee4`
- Source archive SHA-256:
  `93d453d0ad99853b9f1dafeea78fa55da9a838ba71804abcf1aa823537703b79`
- NDK `28.2.13676358`, CMake `3.22.1`, Android API 26 minimum.
- `arm64-v8a` and `x86_64`; `-PinkpilotAbi=x86_64` narrows test builds.
- CPU-only portable kernels; no OpenSSL, subprocess, server, GPU or
  runtime library download. Upstream license notices remain required
  when distributing the native library (llama.cpp is MIT licensed).

Source is fetched only at build time from the immutable commit and
verified archive hash. Weights are imported separately and are not
included in this repository or APK. LFM model weights use Liquid's
separate model license; the application license does not replace it.

## Budgets and format

Default context is 2,048 tokens, output 256 tokens, four CPU threads
at most, and a 120-second load/generation deadline. Inputs exceeding
the actual token budget fail rather than dropping customer facts.
There is no partially truncated success when the output token budget
is exhausted. UTF-16 reply length is checked again in Kotlin.

The GGUF chat template opens its normal reasoning block. The upstream
reasoning-budget sampler caps each reasoning block at one quarter of the
existing output budget, up to 64 tokens, before closing it with the
template's actual end tokens. It re-arms for subsequent blocks; all
reasoning and final text share the same total output/time ceilings.
This avoids relying on LFM3's advisory
`enable_thinking=false`, which can reopen a block and misalign parsing.
Only trusted assistant-prefix tokens initialize the budget sampler.
Final output is parsed using the same llama.cpp template session. Tool calls are
rejected. Template control tokens are tokenized only in template-owned
segments; customer text and memory are treated as user-message data.
Model and template logs are disabled to keep customer text out of logs.

Cancellation is checked between small prefill batches and generated
tokens. CPU graph execution and model loading have native deadline
callbacks. The host's durable revision remains the final cancellation
guard before persisting a draft. Model load cancellation is cooperative
at the next checkpoint; this is not a hard OS process kill.

## Reproducible real-model verification

Official test weights:
`LiquidAI/LFM3-1.7B-GGUF`, revision
`cd0c74c575b5c416f3011b5b4c298077f26ca8c2`, file
`LFM3-1.7B-QAD-Q4_0.gguf` (1,049,615,680 bytes).
SHA-256: `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`.

The module's CMake project builds `inkpilot-inference-smoke` on a Linux
host and the JNI library on Android. The host smoke uses the exact
same engine, sampler, template and output parser as JNI. It covers the
exact production device-test prompt and two ordinary customer questions,
with the 2,048/256-token and 120-second production limits. It checks final
UTF-16 length and the same internal-control markers rejected by Android:

```sh
cmake -S local-inference/src/main/cpp -B /tmp/inkpilot-inference -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/inkpilot-inference --target inkpilot-inference-smoke -j2
/tmp/inkpilot-inference/inkpilot-inference-smoke /path/to/LFM3-1.7B-QAD-Q4_0.gguf
```

Android emulator tests exercise the APK/JNI combination with the pinned
real model separately. A successful model smoke does not establish
physical-phone speed, thermal behavior, carrier delivery or response
quality across real studio conversations.

Primary references: [Liquid's model card](https://huggingface.co/LiquidAI/LFM3-1.7B),
[official GGUF weights](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF),
[llama.cpp Android guidance](https://github.com/ggml-org/llama.cpp/blob/7071ba378afa96dcd69c41e1ec2b4912364dbee4/docs/android.md).
