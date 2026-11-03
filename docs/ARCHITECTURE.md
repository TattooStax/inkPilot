# inkPilot architecture

inkPilot is a standalone Android app. OpenFang is a future optional
integration, never a dependency needed to launch, read records,
draft replies, or operate the studio.

## Module ownership

| Module | Owns | Must not own |
| --- | --- | --- |
| `:app` | Android UI, permissions, SMS role, carrier I/O, device storage and host adapters | A second implementation of agent policy |
| `:agent-core` | Bounded draft workflow, typed inputs/results and host ports | Android classes, network clients, UI, database implementation or SMS sending |
| `:transport` | Inherited SMS transport contracts and lifecycle behavior | New studio records, memory authority or agent orchestration |

Dependencies point from the app to the small reusable modules.
The modules do not depend on the app or on each other. Keep domain
records authoritative in one host store; model prompts and imported
integration payloads are context, not independent databases.

The install ID is `app.inkpilot.android` (`.debug` for debug builds).
The source namespace stays `org.fossify.messages` to preserve
upstream integration and avoid a noisy package-wide rewrite.
Changing identity does not migrate the previous app's private data
or grant default-SMS status; the new installation must obtain that
role through Android.

## First reusable workflow

The core reads authoritative inbound/contact/memory context, asks
the local-model port for reply text, then requests storage of a held
draft. Model output cannot choose a contact or recipient. There is
no tool for sending a message in the core.

The host must atomically check revision, staff pause and event
identity while deduplicating and storing the draft. A Kotlin object
or an in-memory map is not durable delivery protection. Replay,
concurrency and process-death correctness must be verified again
against the eventual production database adapter.

Staff approval and carrier sending remain separate checked actions.
They must recheck recipient, content revision, approval and business
SIM before any send. A generated draft is not evidence of sending,
and carrier acceptance is not evidence of delivery.

## Optional OpenFang integration

When the standalone loop works, add an adapter at the edge. Reuse
the domain contract; do not copy Android screens, a second agent
loop or another record store into OpenFang. A non-JVM host should
use a small versioned request/result protocol rather than import
Android implementation classes.

The adapter should translate OpenFang requests into the same checked
host actions and return explicit results/receipts. It must preserve
contact scope, pause state, revision checks and staff approval.
Installing or removing it must not change standalone behavior.

Transport and packaging are intentionally undecided until needed.
No network listener, remote send endpoint or OpenFang dependency is
added by this foundation. Before distribution, verify license
compatibility for the chosen packaging; existing GPL-3.0 notices
remain intact.

## Keeping the code manageable

- Prefer one responsibility per small file; extract cohesive code
  before extending inherited large activities.
- Keep persistence, inference and integration adapters behind ports.
- Test workflow outcomes and failure boundaries, not implementation
  details. Test Android/provider behavior on devices separately.
- Keep the file-size ratchet green. Explain baseline growth in the
  same commit; never increase a legacy exception ceiling.
- Use the build sequence in `INKPILOT-PLAN.md`; do not create empty
  plugin modules or unrelated framework layers ahead of working code.
