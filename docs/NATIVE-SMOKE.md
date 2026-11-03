# Native LFM3 execution evidence

On 2026-11-03 the same C++ generation engine used by Android JNI was
compiled and run on the Linux x86_64 build host with the official
LFM3-1.7B QAD Q4_0 weights. This is real inference, not a fake model or
an HTTP service. It is not an Android-phone performance claim.

- llama.cpp revision: `7071ba378afa96dcd69c41e1ec2b4912364dbee4`
- GGUF revision: `cd0c74c575b5c416f3011b5b4c298077f26ca8c2`
- GGUF SHA-256: `43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`
- File size: 1,049,615,680 bytes.
- CPU: four inference threads, portable build, GPU/AVX-specific paths off.
- First successful run: 26.32 seconds including loading; peak resident
  memory 1,115,692 KiB (approximately 1.06 GiB).

The first prompt produced an unwanted staff-review introduction. After
asking for the exact customer-facing SMS without introductions or quotes,
the model produced:

> Hi there! I’d be happy to help you book a rose tattoo consultation.
> Which day do you prefer?

The smoke test checked for nonempty final text and absence of reasoning
or tool markup. These synthetic outputs demonstrate execution and prompt
behavior, not comprehensive model quality or permission to send.

Reproduce with CMake and a C++17 compiler:

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
