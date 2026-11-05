# inkPilot

A local-first Android assistant for a tattoo studio. One APK combines
SMS, Phone, Contacts, Studio albums, and a staff Calendar. Agent controls,
held drafts, the studio guide, and model import live in the side menu.

## Implemented

- Separate app identity: `app.inkpilot.android` (`.debug` for debug builds).
- Native SMS conversations/composer and Android phone dialing.
- Local business contacts with editable memory and per-contact pause.
- On-device LFM3 text inference through pinned llama.cpp JNI; import a
  compatible GGUF into private storage. No cloud inference fallback.
- Incoming SMS → durable contact/event → local held draft → staff review.
- Explicit approval and active SIM selection before sending. Durable
  send claims prevent automatic replay; multipart callbacks update the
  native SMS provider, with reconciliation after process restart.
- Studio albums, custom albums, original-preserving image import, and
  album membership independent of file deletion.
- Local appointments/tasks assigned to staff, with artist and shared-room
  conflict checks, completion, cancellation, and timezone storage.
- File-size ratchet, workflow tests, and Android emulator acceptance CI.

These are beta capabilities, not a claim of physical carrier validation.
AI never sends a draft automatically. `SENT` means the carrier accepted
all parts; it does not mean the recipient received or read the message.
Ambiguous attempts are retained and never retried automatically.

## First run

1. Install the debug APK from a successful `inkPilot build` Actions run.
2. In SMS, choose **Set up SMS** and grant the default-SMS role and needed
   permissions. This app is a separate installation; it does not migrate
   another app's private data automatically.
3. Open the side menu's Agent/model controls and import the official
   [LFM3-1.7B QAD Q4_0 GGUF](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF).
   The qualified CI fixture is approximately 1.05 GB; weights are not in
   the APK. The exact revision and checksum are pinned in
   `scripts/inkpilot-device-model.sh`.
4. Enter current studio guidance and customer memory, then enable drafts.
5. Receive a test SMS, review the generated text, choose a business SIM,
   and explicitly approve sending. Complete the physical-device checklist
   before using it for live customer conversations.

After installing weights, drafting and studio records work without an
internet connection. Carrier service is still needed for SMS/calls, and
MMS may require mobile data. Ordinary human calling is implemented;
AI cellular voice and vision are not implemented. The five-tab shell has
in-app SMS/MMS threads and a composer; each entry route still needs device
acceptance. Agent intake currently handles SMS text only.

## Boundaries and remaining work

The pure Kotlin `:agent-core` owns the draft workflow. Android storage,
permissions, UI, and carrier I/O stay in `:app`; `:local-inference` owns
JNI/model execution. `:transport` preserves the inherited SMS bridge.
OpenFang is a future optional adapter, never a startup/runtime dependency.

Calendar hours, time off, reminders, appointment editing and linked
image records have source implementations; their full staff workflow still
needs device acceptance. Automatic memory suggestions and the OpenFang
adapter remain future work. Model quality, battery/thermal behavior,
real SIM delivery, MMS and complete backup/restore remain beta gates.

- [Architecture](docs/ARCHITECTURE.md)
- [Plan and milestone gates](docs/INKPILOT-PLAN.md)
- [Device acceptance](docs/DEVICE-ACCEPTANCE.md)
- [File-size ratchet](docs/FILE-SIZE-RATCHET.md)

## Build and verify

Use JDK 17 and the Android SDK/NDK/CMake versions declared in the Gradle
files. The native dependency is fetched by immutable revision and hash.

```sh
./gradlew :agent-core:test :transport:test :local-inference:testDebugUnitTest :app:assembleFossDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/verify_app_identity.py
scripts/check-file-size-ratchet.sh
```

`inkPilot build` assembles both arm64 and x86_64 debug APK libraries and
checks the packaged SMS-role declarations. `inkPilot device proof` builds
an x86_64 test APK, boots Android, runs real SQLite tests, imports the
checksum-pinned model, disables network access, injects a synthetic SMS,
generates a held draft, tests restart replay, captures UI screenshots,
and exercises sending against the emulator's virtual telephony stack.
Its evidence explicitly distinguishes emulator behavior from a carrier.

## Keeping this manageable

Keep one responsibility per small file. The ratchet records every tracked
text file; ordinary growth requires a deliberate baseline update and a
reason in the commit. New files must stay under 1,000 lines unless an
exception is explicitly reviewed. Imported over-cap files may only shrink.
`--update` cannot grant an exception or relax an inherited ceiling.

## Provenance

Copied with history, branches, and tags from
[TattooStax/inkstax-sms](https://github.com/TattooStax/inkstax-sms) at
`137ad28390e0a543bf47789fdf508b99993ec5df` on 2026-11-03. Source development
remains separate. Upstream Fossify Messages and GPL-3.0 notices are
preserved: see [README-UPSTREAM.md](README-UPSTREAM.md),
[FORK-NOTICE.md](FORK-NOTICE.md), and [LICENSE](LICENSE).
