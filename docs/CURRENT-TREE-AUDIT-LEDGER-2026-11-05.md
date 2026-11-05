# Current-tree source audit ledger and next gates, 2026-11-05

Pinned source: commit `e915096acfa7da10c19538f104f2f4c8aed543ca`, tree
`205b20f64d0cdf23c557214763a25fd82c0dc1d3`. Findings and line references
below describe that tree, before isolated repairs. This is a source and caller
audit of the listed areas, not a whole-repository or physical-device acceptance
claim. A passing isolated branch does not qualify a combined APK.

## Read coverage

The production files in each row were read in full, including functions that
are currently unreachable through an activity alias. Caller and manifest traces
were checked for the findings below. Tests were inspected selectively rather
than counted as complete test-source coverage.

| Area | Production source read | Important boundary |
| --- | ---: | --- |
| Legacy `activities`, `adapters`, `dialogs`, `extensions`, `helpers`, `models` | 95 Kotlin files, 13,044 lines | Manifest aliases, provider and Room calls, old-body reachability |
| `inkpilot/runtime` | 20 Kotlin files, 1,164 lines | Worker, model file, SMS admission, send and tool adapters |
| `inkpilot/phone` | 14 Kotlin files, 783 lines | Manual Telecom call UI and service lifecycle |
| `messaging` | 25 Kotlin files, 1,689 lines | Composer, SMS/MMS handoff, scheduled journal and part callbacks |
| `receivers` | 15 Kotlin files, 1,193 lines | Native inbound, notification actions and send receipts |
| `agent-core` | 11 Kotlin files, 657 lines | Held-only draft orchestration, scoped registry and proposals |
| `local-inference` | 8 Kotlin plus 8 C++/header files, 846 lines | JNI, LFM3 template/sampling, host qualification probes |
| Studio data backing this trace | 11 Kotlin files | `StudioRepository`, `StudioDrafts`, `StudioEvidence`, `StudioInbound`, `StudioLeadIntake`, `StudioPolicy`, `StudioRegeneration`, `StudioDraftAttempts`, `StudioInactiveScope`, `StudioSendClaims`, `StudioSendParts` |

Other studio data/schema files, UI and Studio panels, `:transport`, services,
resources, build configuration, third-party MMS library internals, every test
file, and the rest of the repository are outside this ledger's full-read claim.
Other audit owners are tracing some of those paths. A native/model result, UI
pass, or carrier receipt cannot be established by source reading alone.

## Evidence and prioritized repairs

| Gate | Exact source evidence on pinned tree | Required action and proof |
| --- | --- | --- |
| P0: unqualified local drafts | `AgentRuntime.kt:12-13,51-64` leaves bounded intent unqualified yet still starts the worker. `RequestDisposition.kt:31-47` selects `DraftReplyRenderer.RAW`, free-text Soul, and no evidence reader when the flag is false. `StudioInactiveScope.kt:6-11` blocks only events with an existing stored lead review. `ModelFiles.kt:45-47` pins the imported QAD artifact, but its hash does not qualify raw generated replies. | Disable enablement and raw inference while bounded route is unqualified. Verify UI, queued worker, direct `processPending`, regeneration, and role/model changes produce no model call or new held raw draft. Only enable a reviewed, fixed-vocabulary route after blind model and phone qualification. |
| P0: native action integrity | `DeleteSmsReceiver.kt:18-28` cancels notification before provider deletion. `MarkAsReadReceiver.kt:15-24` cancels before provider read updates; `DirectReplyReceiver.kt:99-117` marks Room read independently. `Context.kt`'s native operations did not require default role plus provider readback before cache changes. | Apply isolated `audit/notification-delete-integrity` and `audit/notification-read-integrity` only after same-tree review. Cover role loss, provider update/delete returning zero, row still present/unread, receiver lifetime, and notification retention. Recheck all `deleteMessage` callers, including `MmsSentReceiver.kt:53-55`. |
| P1: truthful thread preview | `Context.kt:575-599` compares SMS date in milliseconds to MMS date in seconds when the provider snippet is blank; live `ConversationGateway.pageThreads` calls this path. | Integrate isolated `audit/mixed-sms-mms-snippet`. Test newer MMS, newer SMS, equal timestamps and real provider ordering in the combined APK. |
| P1: outbound SIM and role | `ScheduledPreflight.kt:4-8` accepts any nonnegative subscription ID; `ScheduledDispatch.kt:16-29` consumes a one-shot claim afterward. `MessageDispatcher.kt:16-32` checks permission but no default SMS role. | Validate selected subscription against the current active SIM list and role before claiming or touching radio; retain queued/held content on failed preflight. Test removed SIM, revoked role, provider denial, and single-SIM success on device. |
| P1: headless reply channel | `DirectReplyReceiver.kt:75-96` calls `sendMessageCompat` with no attachments. `Messaging.kt:64-99` may choose MMS for long text based on app settings, returning `Unknown` after MMS handoff rather than an SMS accepted result; the held direct reply then stays pending. | Decide channel before handoff; preserve a durable, channel-specific claim/verdict. Prove one long direct reply has one provider row and one staff-visible outcome, including callback failure and process death. |
| P1: same-source gates | Push workflow `inkpilot-build.yml` on pinned tree runs module JVM tests and assembly but omits `:app:testFossDebugUnitTest`. Isolated `audit/app-unit-ci-gate` adds it. Hosted isolated branches still encounter pre-existing `ComposerSharedMedia.kt:36` Detekt `NestedBlockDepth`; several device jobs remain pending. | Integrate app-unit gate with repairs and clear the existing Detekt debt. Require exact combined SHA to pass ratchet, Detekt, app/module tests, device UI suites and role/provider scenarios. Distinguish Maven 429 or emulator failures from code failures. |
| P1: model and phone acceptance | `LocalToolRegistry.kt:5-39` is read-only and scoped; `StudioToolReads.kt:73-75` constructs it but production draft path in `RequestDisposition.kt:27-48` does not call it. `inference_generation.cpp:75-79` rejects native tool calls. `StudioInCallService` provides manual Telecom answer/decline, not agent speech or autonomous answer. | Do not describe a production agent tool loop or AI phone answer. Run the exact bounded prompt and model artifact against blind lead corpus with staff adjudication; then 20 offline phone turns with latency, memory, battery and thermal evidence. Separately prove manual call UI and physical SIM SMS/MMS with receipts on the intended OnePlus. |
| P2: inbox query cost | `ConversationGateway.kt:32-40` calls `getConversations(id)` per displayed thread; `Context.kt:326-349,385` can count unread SMS and MMS over the thread's history. | Batch unread counts only after preserving blocked/archived filters and keyset page behavior. Add provider query-count and mixed page-boundary tests. |

## Simplification dependency

The eight legacy Activity bodies replaced by manifest aliases account for about
4,308 lines; their old adapters and dialogs add about 3,133, and nine old-only
helpers about 1,601. These roughly 9,042 lines are candidates for a separate
subtractive change, not a safe deletion list. `SplashActivity` is still a real
launcher target (`AndroidManifest.xml:111-114,335-339`), and
`ManageBlockedKeywordsActivity` remains declared (`AndroidManifest.xml:205-210`).
`extensions/Activity.kt:114` still uses `ConversationDetailsActivity`'s class
token. Instrumentation tests also reference old class tokens, including
`ShellIngressTest.kt:139-146` and `ComposerPreviewTest.kt:43,59,68,93,153`.
Migrate entry components and tests, preserve required role/intent aliases,
then remove old bodies and dependencies incrementally with compile and route
proof. Existing blocked keyword and unknown-sender settings must remain
manageable in the one-shell UI because they can suppress tattoo leads.

## Updated execution order

1. Integrate the P0 draft-off gate and native Delete/Read integrity, then the
   mixed preview, active SIM, role, and long direct-reply repairs into one
   candidate. Keep held sends and bookings staff-authorized.
2. Run the app-unit CI gate, ratchet, Detekt, module/app JVM tests, and targeted
   provider-deny and one-shell instrumentation at that exact commit. Resolve
   build infrastructure failures separately and rerun the same source.
3. Install that candidate on the intended physical OnePlus with active SIM;
   prove incoming/outgoing SMS and MMS, notifications/receipts, role loss,
   manual call answer/hangup, tab containment, rotation and large text. Record
   APK identity and result for each scenario.
4. Qualify bounded local drafts independently on the pinned model artifact and
   actual phone. Until the blind and on-device gates pass, leave inference off.
5. Remove unreachable legacy UI in a focused change after migrating callers;
   batch unread queries only after correctness and route coverage stabilize.

No current beta percentage or full-code-cleanliness verdict follows from this
ledger. The combined source, phone, model and carrier evidence must converge on
one installable candidate first.
