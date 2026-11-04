# inkPilot full code audit and beta repair plan

Audit date: 2026-11-04. Repository: `TattooStax/inkPilot`, commit `0b2d8fc08803a2aac029283266a71dfea27cac40`. This is a source audit and a limited physical-device validation, not a claim that every behavior was executed on a carrier network. The five-tab text-first beta and the existing `docs/INKPILOT-PLAN.md` are the product contract. AI voice answering remains a later milestone.

## Decision

inkPilot is one Android package but presently has **two visible applications inside it**. `StudioHomeActivity` draws the inkPilot tabs; a conversation tap launches the inherited Fossify `ThreadActivity`, replacing the shell. A draft tap, new-message tap, SMS settings, notifications, shortcuts, forwarding, and several auxiliary screens can take the same path. The user's report is correct. An SMS tab that hands the whole conversation to the inherited UI does not meet the product contract. The SMS inbox, thread, composer, attachments, delivery state, and held agent draft must live in inkPilot's persistent canvas. Android's role/permission dialogs and system call UI are explicit OS handoffs, not substitute product screens.

The radio/provider code and much of its test coverage are useful; the UI ownership, durable state, and integration boundaries need repair before a studio beta. Do not reskin `ThreadActivity` or embed its adapter unchanged: it casts the host to `ThreadActivity`, uses `SimpleActivity` dialogs, and contains its own lifecycle and composer. Extract its carrier operations behind a presentation-independent interface, keep the proven provider handling, and migrate screen routes into one shell.

## Audit method and coverage

The audit divided independent, nonediting reads across UI/navigation, SMS/MMS, local agent/data/inference, Studio/phone/models, transport, tests/build/resources, and a coverage reconciliation. Each lane read assigned source files end to end, including tests and critical XML. Findings below cite exact locations. The [four-part symbol index](audit-index/README.md) accompanies this report for source navigation; a static symbol list is not a substitute for behavior review. We did not execute all GUI permutations, inspect binary artwork pixel by pixel, or read every localized translation sentence. Those are called out as remaining validation, not silently counted as code review.

| Lane | Scope reviewed | Count |
| --- | --- | ---: |
| inkPilot and legacy UI/navigation | UI, Studio panels, adapters, dialogs, activities, navigation/media helpers | 57 Kotlin, 7,938 lines |
| SMS/MMS and carrier flows | conversations, providers, messaging, receivers, helpers, `Context` extension, headless service | 38 Kotlin, 7,277 lines |
| local agent/data/inference | persistence, runtime, studio, agent-core, native inference, associated tests | 48 production/native files, 2,642 lines; 23 related tests |
| optional transport | all pure module and Android bindings | 80 files, 10,601 lines; 16 pure tests |
| Studio/phone/model cross-check | 10 Studio sources, contact/phone panels, 23 models, 5 DAOs, messages DB | cross-checked with lanes above |
| root and coverage reconciliation | remaining extensions, App, constants, Activity extension, service | 17 small Kotlin files; see index |
| build/tests/resources | 41 app tests, CI, scripts, Gradle/manifest, all 119 base code-bearing resource XML | 3,817 test lines; 200 XML parse clean |

Counts overlap where two reviewers independently cross-checked critical files. They must not be summed as unique source files. `git ls-files` at this commit contains 656 tracked items: 231 production Kotlin and 41 Kotlin tests, six native C++/headers, six Gradle KTS, nine scripts, and 208 XML. All executable/source files and all 119 base code-bearing resource XML plus three variant value XML were line-read; 81 locale XML and translated store listings were parsed/inventoried without semantic translation review. Binary assets have no lines to read. The symbol index covers 294 code-source files and 1,939 named symbols in four ratchet-safe shards. No open GitHub issues or pull requests were listed at audit time; that is not evidence of zero product defects.

The codebase has a careful local agent core, checked staff approval and durable send receipts. Its biggest maintainability problem is ownership, not formatting: two UI architectures and two contact/message worlds coexist. `ThreadActivity` is 2,210 lines, the `Context` extension 1,394, and the chronological transport README 1,118; the file-size ratchet carries four existing over-cap exceptions. The lint baseline suppresses 224 findings. This is a workable fork with substantial tests, but it is not yet a clean, unified professional beta. A comparison with Stax Cloud would require auditing that separate repository under the same criteria.

## Runtime and ownership map

| Entry or action | Current path | Desired owner |
| --- | --- | --- |
| launch | `SplashActivity` / `StudioHomeActivity` and inherited `MainActivity` entry paths | one inkPilot shell, with compatibility intent forwarding |
| inbox tap/new SMS | `MessagePanel` → `ThreadActivity` / `NewConversationActivity` | SMS tab → in-shell thread/compose routes |
| inbound SMS | Android SMS receiver → Telephony provider and cache → `AgentSmsIntake` → Studio event → WorkManager local LFM3 draft | same provider, one thread/contact record, held draft inline |
| human SMS/MMS | inherited composer → `MessagingUtils`/`Messaging` and provider/SmsManager/MMS service → callbacks | in-shell composer using extracted carrier service, durable send state |
| approved agent reply | held draft → staff confirmation → send claim/parts → SmsManager callback | in-shell draft card and visible outgoing provider/thread row |
| optional companion bridge | paired inbound queue; periodic remote pickup → `OutboundWorkSender` → SmsManager, no Telephony provider row | separate optional integration with provider-backed visible sends or outside beta |
| contacts | Studio memory record versus Android/Fossify contact and thread identity | linked canonical studio contact with native address mapping |
| Studio/calendar | separate lightweight tables/panels | shared customer/project/artist/appointment references and agent-visible actions |
| phone | Studio `ACTION_DIAL` → system dialer | beta scope explicitly chosen; AI cellular answering requires its own feasibility gate |

## Findings: UI and product boundary

| ID | Severity | Evidence and consequence | Repair / acceptance |
| --- | --- | --- | --- |
| UI-01 | blocker | `MessagePanel.kt:38-40,55-58`, `DraftPanel.kt:35-39`, `StudioNavigation.kt:59-62` start inherited activities. `NotificationHelper.kt:58-69,202-209`, `ShortcutHelper.kt:49-58`, `HeartbeatNotification.kt:39-45`, forwarding and `extensions/Activity.kt:113-118` expose further escapes. The five-tab shell disappears. | Typed in-shell destinations, compatibility redirects; every entry remains in shell with correct Back and app-lock state. |
| UI-02 | blocker | `DraftPanel.kt:26-49` presents held drafts in a separate list/dialog. A staff member cannot see the proposed answer in the live customer thread. | Inline, explicitly held, editable/rejectable draft in the relevant thread; sent/rejected/stale outcomes shown there. |
| UI-03 | high | `MessagePanel.kt:45-63` builds at most 150 inbox rows in a `ScrollView`/`LinearLayout`. Search, pagination, archive, unread controls exist only in the old UI. | Virtualized/paged inbox with parity for essential operations. |
| UI-04 | medium | `StudioHomeActivity.kt:98-119` recreates canvas/footer on render and telephony observer refresh; focus, draft text, and scroll may reset. | State holder and incremental list updates; device test rotation, notification, incoming SMS while composing. |
| UI-05 | medium | `StudioNavigation.kt:22-45` uses five equal items and 11sp labels; `StudioUi.kt:18-23` hard codes colors; hints disappear when typing. | Large-font, TalkBack, contrast and narrow-screen audit; persistent input labels. Static risk, not yet measured accessibility failure. |
| UI-06 | medium | `ThreadAdapter.kt:95-101,252-256` expects `SimpleActivity` and casts to inherited thread; attachment/photo callbacks have stale-row/null risks. | Extract callbacks, avoid activity casts, retain MMS rendering and media lifecycle. |
| UI-07 | medium | Legacy send button is announced as “OK” (`layout_thread_send_message_holder.xml:176`), new conversation confirm image lacks a description, and lint baseline suppresses 15 `ContentDescription` issues. | Name actions accurately, audit new in-shell controls with TalkBack and clear the relevant baseline entries. |

## Findings: message integrity and recovery

| ID | Severity | Evidence and consequence | Repair / acceptance |
| --- | --- | --- | --- |
| MSG-01 | blocker | SMS and MMS provider IDs occupy separate namespaces but `Message.kt:18,44-69`, `MessagesDao.kt:29-50`, `ThreadAdapter.kt:163-169,660-684`, `ThreadActivity.kt:1699-1708` use a bare numeric ID for cache/selection/diff. Equal IDs can overwrite or misidentify a message. | Typed `(kind, providerId)` identity end to end; explicit lossless Room migration, including drafts/scheduled/archive/recycle; collision fixture. |
| MSG-02 | high | `ThreadActivity.kt:1674-1704` clears composer and attachments after `Messaging.kt:75-88` can swallow failure; attachment read failure can degrade to text-only send (`MessagingUtils.kt:166-195`). | Durable composer until carrier submission succeeds, explicit attachment failure, status and retry; failed-send device test. |
| MSG-03 | high | `ScheduledMessageReceiver.kt:61-67` deletes scheduled record immediately after posting send, before verdict; `Context.kt:1351` expires old rows. | Transactional scheduled state machine; keep until confirmed, retry/reconcile ambiguous outcome. |
| MSG-04 | high | `MmsSentReceiver.kt:25-50` deletes original on failed resend. `ThreadActivity.kt:343-352` discards media draft on navigation. | Preserve original and media draft until successful verified replacement. |
| MSG-05 | high | `SmsReceiver.kt:45-58` filters before provider insertion; blocked carrier arrivals can be dropped from the durable record. Provider insert failure can still drive cache/agent with id zero (`Context.kt:821-851`). | Store provider event first; mark filtered; require valid source identity and recover scan before agent side effects. |
| MSG-06 | high | `SmsReceivedReceiver.kt:42-83` permission-only mode omits `AgentSmsIntake`; MMS inbound is not passed to the local text agent. | Either make default-SMS role a stated beta precondition or implement equivalent durable intake; annotate MMS media and do not infer image content. |
| MSG-07 | medium | Async receiver work has no `goAsync`; `DirectReplyReceiver.kt:31-36` uses stored subscription ID as an index; `ThreadActivity.kt:1076-1127` can append duplicate SIM selections. | Bounded receiver lifecycle, exact subscription routing, dual-SIM tests. |
| MSG-08 | medium | `HeadlessSmsSendService.kt:16` corrupts `mmsto:` URI by removing `mms`; service swallows errors. Import dedupe and provider-delete/cache-delete paths can diverge. | Correct URI, visible errors, source-keyed import and provider-consistent deletion. |
| MSG-09 | medium | `Message.kt:60-69` and `Conversation.kt:29-37` diff comparisons omit status/read/subscription or scheduled/archive changes. | Include all visible state, test callback refresh. |
| MSG-10 | medium | `MessagesDatabase.kt:24-33,55-70` calls conversations cache rebuildable but stores human draft/scheduled/archive/recycle, then uses destructive fallback. | Treat as user data; migration and backup/recovery gates, never destructive fallback for released schema. |
| MSG-11 | high | `AndroidManifest.xml:251-254` exports MMS receiver without a sender permission or intent filter. Cached `mmslib` `MmsReceivedReceiver.onReceive` accepts a caller-supplied `file_path`, reads it into memory and may delete it after parsing/persisting. An explicit broadcast can enter this path. Exploitability beyond that surface is unproved. | Check the library/platform callback contract, constrain component export/permission or validate caller/file, then spoof-broadcast and legitimate MMS regression tests. |

## Findings: local agent, contacts, Studio, calendar, and phone

| ID | Severity | Evidence and consequence | Repair / acceptance |
| --- | --- | --- | --- |
| AG-01 | high | Editing Soul/contact memory/pause/model invalidates held draft, but `StudioInbound.kt:73-76` excludes any event with a held row and `StudioDatabase.kt:59-71` uniquely keys it; manual retry cannot regenerate. | Explicit staff-initiated revisioned regeneration, retaining immutable receipt/send tombstones and prior draft history. |
| AG-02 | high | `SmsIntakeRecovery.kt:19-25,45-60` scans at most 200 native inbox rows per wake; no continuation is scheduled. | Cursor/page continuation until caught up, bounded and restart-safe; 201+ fixture. |
| AG-03 | high | `StudioSendParts.kt:28-51` treats first multipart failure as terminal, ignores later success; `AgentRuntime.kt:29-35` can block committed work after recovery error; `LocalDraftWorker.kt:8-14` makes transient errors terminal. | Per-part verdict reconciliation, UNKNOWN/manual review, isolation and retry/backoff. |
| AG-04 | high | `DraftPromptEncoding.kt:7-15` supplies latest SMS, Soul and a free-text note only. No thread history, album, appointment, calendar tool, or MMS understanding. | Explicit bounded local context with provenance; scoped calendar proposals and staff review; image handling only after local vision is proved. |
| AG-05 | medium | `ModelFiles.kt:24-53` only checks GGUF magic/size before “imported”; architecture/template fail later; import can stale drafts before rename, and does not wake pending work. | Validate compatible LFM3/template at import, atomic revision, schedule pending. |
| AG-06 | medium | Cancellation callback does not bump durable revision despite `HeldDraftStore.kt:47-50` contract. A stop racing final hold can commit a held draft, though it cannot auto-send. | Durable cancel generation checked in hold transaction or narrow invariant with race test. |
| DATA-01 | high | `studio_contacts` memory/pause and Android/Fossify identities are separate; NANP address variants can yield duplicate contact records. | Canonical identity mapping with ambiguity review, not unsafe blind merge; thread/contact/agent share record. |
| DATA-02 | high | Albums have local files and galleries but no direct MMS attachment import or link to contact/project/appointment/artist (`StudioFeatureSchema.kt:8-24`, `StudioAlbumsPanel.kt:47-55`). | Contextual import and linked references with staff permission/review. |
| DATA-03 | high | Calendar is manual only; no client, hours, time off, reminder, edit/reschedule or agent scheduling action (`StudioCalendarPanel.kt:24-88`). Default task room “Studio” causes false conflicts for unrelated staff (`CalendarEditor.kt:31-33`). | Shared availability model and action proposal, calendar conflict tests for staff/room/timezone. |
| DATA-04 | medium | Contact memory and pause save in two transactions (`ContactPanel.kt:72-78`); free-text memory lacks source/review/forget; `StudioDatabase` v1 upgrade throws. | Atomic edit, source tagged memory entries, export/forget and data-preserving migration. |
| DATA-05 | medium | Calendar stores zone but displays in device zone; DST repeat ambiguous; subminute appointment duration slips past validator. | Explicit studio/time-zone display, DST policy and validation tests. |
| PHONE-01 | beta-scope decision | Phone panel uses `ACTION_DIAL`; there is no call answering, in-call UI, or local audio agent. Legacy `ACTION_CALL` exists elsewhere. | State text-first beta limits explicitly; choose Android dialer-role/telecom and audio approach in separate feasibility milestone before claiming voice. |

## Findings: optional bridge, quality gates, and documentation

| ID | Severity | Evidence and consequence | Repair / acceptance |
| --- | --- | --- | --- |
| BR-01 | blocker if enabled | `OutboundWorkSender.kt:23-35,87-128` sends through `SmsManager` without provider row, explicitly acknowledged in `transport/README.md:1003-1010`; agent-originated bridge replies are invisible in thread. | Provider-backed send and status or keep bridge outside local-first beta entirely. |
| BR-02 | high if enabled | `OutboundPickupSchedule.kt:64-75` uses 15-minute periodic polling; OS may delay more. Non-default mode also lacks provider copy and source ID for held inbound. | Do not represent as live local agent path; define supported role and latency contract. |
| BR-03 | medium | PendingIntent keyed by destination+millisecond and reused across multipart; data classes expose credentials in generated `toString`; cross-run idempotency is unproven. | Unique part/request identity, redact secret values, crash/replay tests; secret issue is preventive, no observed leak. |
| BR-04 | medium | Pairing pure test accepts `http://[::1]` (`PairingTest.kt:209-215`), but Android network security config names only `localhost`/`127.0.0.1`; IPv6 loopback cleartext may be rejected at runtime. | Align accepted endpoints and network policy; prove on-device. Inference, not observed failure. |
| QA-01 | high | CI includes emulator SMS injection but no carrier delivery proof; Android bridge binding has no dedicated integration tests. The 18 Android instrumentation files (47 tests) were not run during this source audit. | ADB/emulator routing gate plus real SIM SMS/MMS, failed send, multipart, dual SIM and process death matrix. |
| QA-02 | high for release | `.github/workflows/release.yml:10-17` inherits Fossify shared workflow and secrets; `fastlane/Appfile:2` targets `org.fossify.messages`, inherited listing/branding. | Own release pipeline and package/listing audit before publication. |
| QA-03 | medium | `strings.xml:205-207` and store listing say Internet is MMS-only, while optional pairing/heartbeat/pickup uses network. 49 translated locales also define this FAQ, and none translate new bridge labels. `docs/ARCHITECTURE.md:24-32,68-74` misstates rebuildability and remote behavior; device acceptance calls legacy UI later cleanup. | Reconcile behavior and disclosure in every shipped locale, architecture and one-shell beta acceptance. |
| QA-04 | medium | CI lacks release/minified/lint static gates; release lint is disabled. Lint baseline suppresses 224 findings (118 missing translations and 15 content descriptions), and Detekt baseline has 225 entries. 81 locale XML and store metadata are inventoried but not line-reviewed here. All 119 base code-bearing resource XML were read and 200 resource XML parsed. | Add gates tied to release risk and language QA, then retire baseline entries as screens migrate. |

## Verified versus unverified

- The CI-equivalent command on Victus passed after using its installed JDK 17 and Android SDK with a writable home temp directory: `:agent-core:test :transport:test :local-inference:testDebugUnitTest :app:assembleFossDebug`. All **223/223 module tests passed** and the dual-ABI `inkPilot-26-foss-debug.apk` assembled (SHA-256 `62d595355a2c628e959e0cf6929f7b700f5e4349c13a7969a4919fd690baa831`). Packaged-manifest SMS-role qualification and static identity/ratchet checks passed. No instrumentation suite, release/minified build or lint run was performed for this audit.
- On the OnePlus 6T, inkPilot remained the default SMS app. A real incoming reply was present in Telephony, recorded as a Studio inbound event, and yielded a **held** local LFM3 draft roughly 25 seconds later. It was not automatically sent. Activity logs also showed `ThreadActivity` when the conversation was opened. This proves the reported UI jump and one inbound text path, not complete SMS/MMS reliability.
- An earlier MMS attempt reached the platform/provider path with HTTP 200. Recipient media rendering and end-to-end carrier delivery are **not** established by that status alone. No local AI phone-call answer was demonstrated. No fresh messages or phone changes were made for this audit.
- Source findings indicate reachable failures; severity is assigned by possible customer impact. App-lock bypass, large-font clipping, duplicate bridge SMS after crash, and exactly-once delivery remain hypotheses requiring explicit device/integration tests.

## Revised implementation order and beta gates

1. **Freeze current behavior and protect data.** Add a failing UI ownership instrumentation test for inbox → thread → compose → held draft → Back, plus notification/shortcut/share/deep links. Capture existing provider and Room rows on a test fixture. Add typed SMS/MMS identity and a migration that preserves *human* drafts and scheduled messages. Fix the composer, scheduled, MMS resend, provider/filter and recovery loss paths. No destructive DB migration.
2. **Move messaging into one shell.** Create typed routes in `StudioHomeActivity` for inbox, thread, compose, review and settings. Extract provider read/send/attachment operations from `ThreadActivity` and adapter casts. Use a virtualized inbox and thread, maintain scroll/composer through refresh/rotation. Render held draft and all outgoing statuses inline. Forward old activity/system intents into shell, then test every entry route. The old screens may remain as nonvisual compatibility gateways until feature parity, but may not replace the shell.
3. **Unify studio records.** Map native addresses to one studio contact, link thread/lead/memory/albums/artist/appointment, add MMS attachment import, memory provenance/edit/forget, and staff-visible proposal controls. Resolve NANP ambiguity explicitly.
4. **Make calendar and agent useful together.** Add staff availability, hours/time off, edit/reschedule, room-aware conflicts, reminders and timezone rules. Give the LFM3 text agent bounded history and *proposal-only* appointment actions using that same calendar; staff approve sends and bookings.
5. **Decide optional companion boundary.** Keep remote bridge disabled/outside text-first beta until its sends appear in provider/thread, its latency contract is honest, and crash/replay tests prove idempotency. Keep it modular for possible OpenFang reuse; the standalone app must work without it.
6. **Prove the product on the device.** Walk a real lead from inbound SMS/MMS → unified contact → held answer → staff-approved visible reply → reference album → assigned artist → appointment and reminder, offline with local LFM3. Test failed send, restart, duplicate provider IDs, delayed callbacks, and no shell escape. Own release/branding/privacy copy before beta distribution. Voice answer is a separate, explicit feasibility and acceptance gate.

An honest rough estimate is **about 40% toward the text-first functional beta**, based on existing local SMS reception, local held drafting, basic albums/calendar, and the substantial unfinished ownership/integrity/workflow gates above. This is a planning estimate, not test coverage or a measured productivity percentage. The complete lead-to-appointment acceptance path has **not passed**, so it is not beta ready. Do not attach a percentage to voice answering; no implementation or device proof exists.

## Acceptance checklist

- Every SMS/MMS thread, new conversation, attachment, settings, contact, held draft, notification, shortcut and supported share/deep link remains in inkPilot's five-tab canvas; Back and app lock work.
- Inbox search/pagination covers beyond 150 rows; accessibility at large font and TalkBack is measured.
- Each SMS/MMS carries typed provider identity; a collision, restart and migration preserve all human drafts, scheduled sends, archive/recycle and delivery state.
- Failed, partial and ambiguous SMS/MMS sends preserve the composer and expose accurate status/retry without double-send.
- Incoming provider records recover beyond 200 and cannot disappear due to filters, receiver lifetime or worker failure.
- Contact, memory, conversation, image and calendar point to the same customer; agent actions are local, bounded and staff reviewed.
- The exact carrier/phone matrix is evidenced with logs that omit personal message bodies and numbers; release metadata and privacy claims match enabled network behavior.

## Index and limitations

The companion symbol/file index should be generated from this same commit with paths, line counts, type/function declarations and source bucket. It supports review and future incremental ratchets. Generated code/translation text, binary assets and store language QA require their own review. A static audit cannot guarantee finding every runtime bug; the device gates above define what would change that confidence.
