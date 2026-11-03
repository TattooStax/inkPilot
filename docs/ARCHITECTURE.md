# inkPilot architecture

inkPilot is a standalone Android app. OpenFang is a future optional
integration, never a dependency needed to launch, read records,
draft replies, or operate the studio.

## Module ownership

| Module | Owns | Must not own |
| --- | --- | --- |
| `:app` | Android UI, permissions, SMS role, carrier I/O, device storage and host adapters | A second implementation of agent policy |
| `:agent-core` | Bounded draft workflow, typed inputs/results and host ports | Android classes, network clients, UI, database implementation or SMS sending |
| `:local-inference` | Pinned llama.cpp JNI, GGUF templates and bounded LFM3 CPU inference | Model downloads, SMS sending, studio records or cloud fallback |
| `:transport` | Inherited SMS transport contracts and lifecycle behavior | New studio records, memory authority or agent orchestration |

Dependencies point from the app to the small reusable modules.
The inference module implements the core's model port; neither depends
on the app. Keep domain
records authoritative in one host store; model prompts and imported
integration payloads are context, not independent databases.

`inkpilot-studio.db` is the one canonical business database: contacts,
memory, events, policy, held drafts, send claims, albums and calendar.
It uses checked SQLite transactions and explicit schema versions, with
no destructive fallback. The inherited `conversations.db` is an SMS UI
cache that can be rebuilt; it never owns studio business data.

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

The SQLite host atomically checks revision, staff pause and event
identity while deduplicating and storing the draft. Source edits,
new incoming messages, global pause, Soul changes and customer-memory
changes invalidate active inference. Real-database tests cover replay,
concurrent writers, tombstones and restart persistence.

Staff approval and carrier sending remain separate checked actions.
They must recheck recipient, content revision, approval and business
SIM before any send. A generated draft is not evidence of sending,
and carrier acceptance is not evidence of delivery.

The host persists a send claim before invoking SmsManager, records each
multipart result, and never automatically retries an ambiguous send.
Native-provider updates have a persistent reconciliation queue separate
from sending. Failed draft attempts are recorded per event/revision so
one unprocessable message cannot starve later contacts; staff can retry.

## Optional OpenFang integration

When the standalone loop works, add an adapter at the edge. Reuse
the domain contract; do not copy Android screens, a second agent
loop or another record store into OpenFang. A non-JVM host should
use a small versioned request/result protocol rather than import
Android implementation classes.

The future adapter should translate OpenFang requests into the same checked
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
