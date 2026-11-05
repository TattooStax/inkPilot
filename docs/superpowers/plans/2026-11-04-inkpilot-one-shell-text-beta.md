# inkPilot One-Shell Text Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Ship a fully local, staff-controlled tattoo-studio text beta and native manual Phone on the OnePlus, with inkPilot owning every SMS/MMS screen.

**Architecture:** Preserve Android Telephony/SmsManager and the checked local agent core; migrate message identity and human state before replacing inherited activities. Use one typed navigation shell and one linked Studio record store. Agent tools are typed, scoped proposals over that store; staff approval remains the only send/book action.

**Tech Stack:** Kotlin/Android 11, Room/SQLite, WorkManager, SmsManager/MMS library, JNI llama.cpp GGUF LFM3, JUnit Android instrumentation, Gradle, native RecyclerView.

**Spec:** docs/INKPILOT-PLAN.md, docs/DEVICE-ACCEPTANCE.md, docs/INKPILOT-FULL-CODE-AUDIT-2026-11-04.md (audit PR #1). Base source 0b2d8fc; verify current main first. Voice answering is a separate gated milestone.

## Global Constraints

- One standalone APK; SMS, Phone, Contacts, Studio and Calendar stay in the inkPilot shell; Soul, memory and agent controls are contextual. System role/permission and designed call surfaces are explicit OS handoffs.
- Local LFM3, no required backend. The model never chooses a recipient or directly sends/updates memory/books. Staff approval and checked transactional revision precede every outbound action. UNKNOWN send states are never retried automatically.
- Preserve native provider history, human drafts, scheduled sends, archive/recycle, originals, Studio records and send tombstones. No destructive migration or live phone reset. No blind number alias merge.
- Isolated worktree and durable ledger; task-by-task independent green commits and spec/quality review; no main merge until full device acceptance. Keep scripts/check-file-size-ratchet.py green, extract from ThreadActivity.kt and extensions/Context.kt rather than growing them.
- Victus build environment: JAVA_HOME=/home/shawnlowe/inkstax-android/jdk17; ANDROID_HOME=/home/shawnlowe/inkstax-android/sdk; TMPDIR=/home/shawnlowe/inkPilot-physical-test-20261104/tmp; JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/shawnlowe/inkPilot-physical-test-20261104/tmp. Full gate: ./gradlew :agent-core:test :transport:test :local-inference:testDebugUnitTest :app:assembleFossDebug --console=plain --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g; then python3 scripts/verify_app_identity.py and python3 scripts/check-file-size-ratchet.py.
- Android test build: ./gradlew :app:assembleFossDebugAndroidTest. Task 2 creates scripts/run-inkpilot-test-class.sh with arguments EMULATOR_SERIAL and FQCN; it must install the two test APKs and run only adb -s EMULATOR_SERIAL shell am instrument -w -e class FQCN app.inkpilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner. The runner refuses a non-emulator serial. Never run scripts/inkpilot-device-run.sh on the physical OnePlus: it changes SMS role, model and network settings.
- Physical target adb -s 192.168.40.149:5555 (another device is attached). One exclusive device operator. Preserve current Android 11 default SMS role, OnePlus Dialer default and customer data until a controlled, reversible test. Redact numbers/bodies/screenshots in reports.

## Review Focus

- Same numeric ID in SMS, MMS, SCHEDULED_SMS and SCHEDULED_MMS (the latter two are local): all four remain distinct through Room, selection, alarm, recycle and restart; Tasks 3–4 and 6 test it.
- Legacy recycle row without source kind when both provider IDs match: retain an unresolved record and require review instead of assigning/deleting either; Task 4 tests it.
- Failure between dispatch and callback, including late multipart success: composer, scheduled state and UNKNOWN receipt remain honest; Tasks 5–6 test it.
- Cold notification/share/Up intent while app locked or URI grant expires: no content before unlock and recipient/media survive; Task 11 tests it.
- Role/permission granted after app startup and second SMS observer registration failing: contact/SMS observation recovers with no leaked registration; Tasks 8 and 13 test it.

---

### Task 1: Remove destructive legacy first-launch clearing

**Files:** Modify app/src/main/kotlin/org/fossify/messages/extensions/Context.kt:1259-1265 and activities/MainActivity.kt:105; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/LegacyFirstLaunchTest.kt.
**Interfaces:** Consume messagesDB and config.wasDbCleared; produce clearAllMessagesIfNeeded(callback: () -> Unit) with a data-preserving initialization contract.
- [ ] Red: test preservesScheduledOnFirstLaunch inserts scheduled, archived/recycled and media cache rows, sets wasDbCleared=false, invokes MainActivity bootstrap, asserts every row and callback remain.
- [ ] Run red: ./gradlew :app:assembleFossDebug :app:assembleFossDebugAndroidTest -PinkpilotAbi=x86_64; install both generated foss/debug APKs with adb -s emulator-5554 install -r; run adb -s emulator-5554 shell am instrument -w -e class org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest app.inkpilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner. Expect scheduled row missing.
- [ ] Implement: remove messagesDB.deleteAll and replace with non-destructive cache reconciliation; mark flag only after successful initialization.
- [ ] Run green: same class plus full module/debug gate and ratchet; expect all fixture rows unchanged.
- [ ] Commit: fix: preserve messages on legacy first launch.

### Task 2: Establish deterministic Android test runner and UI baseline

**Files:** Create scripts/run-inkpilot-test-class.sh and app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/ShellBaselineTest.kt; modify .github/workflows/inkpilot-device.yml.
**Interfaces:** Produce runner(serial: emulator-*, class: FQCN) as executable shell contract; later tasks use its exact two arguments.
- [ ] Red: script test rejects physical serial 192.168.40.149:5555 and ShellBaselineTest records current inherited ThreadActivity/new compose transition while verifying inkPilot initially holds SMS role; fixtures contain no live customer data.
- [ ] Run red: run the script with physical serial and expect refusal; run ShellBaselineTest before implementation and expect the explicit legacy-transition baseline assertion.
- [ ] Implement: emulator-only serial guard, APK install/class instrument and redacted evidence path; add workflow invocation. Capture OnePlus screenshots/UI tree separately without role/data mutation.
- [ ] Run green: scripts/run-inkpilot-test-class.sh emulator-5554 org.fossify.messages.inkpilot.smoke.ShellBaselineTest; full gate and ratchet.
- [ ] Commit: test: isolate emulator UI baseline runner.

### Task 3: Define four-way message identity

**Files:** Create app/src/main/kotlin/org/fossify/messages/messaging/MessageKey.kt; modify models/Message.kt helper methods only (no Room schema change yet); Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/MessageKeyTest.kt.
**Interfaces:** Produce enum MessageOrigin { SMS, MMS, SCHEDULED_SMS, SCHEDULED_MMS }, data class MessageKey(origin: MessageOrigin, id: Long), and fun Message.key(): MessageKey; Task 4 consumes them.
- [ ] Red: equal id=42 for all four origins yields four unequal keys and stable UI IDs; either scheduled local row never aliases a provider row or each other.
- [ ] Run red: scripts/run-inkpilot-test-class.sh emulator-5554 org.fossify.messages.inkpilot.data.MessageKeyTest; expect missing types/function.
- [ ] Implement: origin-aware model/selection identity with unambiguous scheduled classification; no DB schema change yet.
- [ ] Run green: same class, full gate, ratchet.
- [ ] Commit: feat: type SMS MMS and local scheduled IDs.

### Task 4: Migrate Room v11 without guessing ambiguous recycle identity

**Files:** Modify databases/MessagesDatabase.kt, models/Message.kt, models/RecycleBinMessage.kt, interfaces/MessagesDao.kt, activities/ThreadActivity.kt, adapters/ThreadAdapter.kt and extensions/Context.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/MessageMigrationTest.kt.
**Interfaces:** Consume MessageKey; produce Room v12 messages primary key (id,is_mms,is_scheduled), recycle_bin_messages primary key (id,kind_code) with LEGACY_UNKNOWN=4, and origin-aware DAO get/delete.
- [ ] Red: new v12 fixture stores SMS/MMS/scheduled SMS/scheduled MMS with id 42 as four rows. Separate v11 fixture has one cached row at id 42 plus native provider SMS+MMS, an existing human scheduled/draft, archive and ambiguous recycle id 42: assert every extant human row survives, provider rows are recovered when available, recycle ambiguity is quarantined and neither provider message is deleted. An overwritten local scheduled row absent from v11 and Telephony cannot be reconstructed; report that limit rather than claiming recovery.
- [ ] Run red: scripts/run-inkpilot-test-class.sh emulator-5554 org.fossify.messages.inkpilot.data.MessageMigrationTest; expect v11 collision/unresolved assertions fail.
- [ ] Implement: transactional v11->v12 messages table copy/index with composite (id,is_mms,is_scheduled) and recycle (id,kind_code). Set legacy tombstones to LEGACY_UNKNOWN=4, only reconcile if native provider yields a unique candidate; otherwise keep for staff review and never delete either candidate. Preserve MMS-only message_attachments/attachments and nested JSON unchanged. Requery provider for lost cache rows where possible; remove fallbackToDestructiveMigration; make Context.deleteScheduledMessage and recycle callbacks accept MessageKey. Fix Message/Conversation visible content diff status/read/subscription/archive.
- [ ] Run green: same class and existing data tests, full gate and ratchet; verify upgrade, rollback/restart and no destructive path.
- [ ] Commit: feat: migrate typed message cache without deleting user data.

### Task 5: Make dispatch acceptance typed and composer durable

**Files:** Create messaging/DispatchOutcome.kt and messaging/MessageDispatcher.kt; modify messaging/Messaging.kt, MessagingUtils.kt and activities/ThreadActivity.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/DispatchOutcomeTest.kt.
**Interfaces:** Produce data class OutgoingMessage(threadId: Long, recipient: String, body: String, attachmentUris: List<Uri>, subscriptionId: Int) and MessageDispatcher.submit(request: OutgoingMessage): DispatchOutcome, sealed Accepted(providerKey: MessageKey?), Rejected(reason: String), Unknown(claimId: String); Task 10 composer consumes them.
- [ ] Red: unreadable MMS URI, denied SEND_SMS and transport failure leave text/URI/recipient draft intact, return Rejected or Unknown, and never send text-only fallback.
- [ ] Run red: emulator runner DispatchOutcomeTest; expect old Unit/toast path clears composer.
- [ ] Implement: persist per-thread composer before dispatch, surface typed result; only clear after acknowledged acceptance, preserve attachments through uncertain outcome.
- [ ] Run green: same class, full gate and ratchet.
- [ ] Commit: fix: preserve composer across failed sends.

### Task 6: Durable scheduled and multipart verdict state

**Files:** Modify messaging/ScheduledMessage.kt, receivers/ScheduledMessageReceiver.kt, MmsSentReceiver.kt, inkpilot/data/StudioSendParts.kt and extensions/Context.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/ScheduledVerdictTest.kt and StudioSendPartsTest.kt.
**Interfaces:** Consume MessageKey/DispatchOutcome; produce scheduled queued->dispatching->confirmed|failed|UNKNOWN state, per-part reconciliation and kind-aware alarm lookup.
- [ ] Red: alarm post fails then process dies; scheduled row survives. Scheduled SMS and MMS with same synthetic ID get distinct alarms/lookup; old raw-ID alarm with ambiguous match fails closed without double-send. Failed MMS resend preserves original. First part fails then later part succeeds; status remains partial/UNKNOWN.
- [ ] Run red: emulator runner ScheduledVerdictTest then StudioSendPartsTest; expect premature deletion/first-failure terminal state.
- [ ] Implement: persist claim before send, retain queued item until callback, reconcile all parts and stop one-minute expiry from deleting unresolved scheduled messages. Alarm PendingIntent identity includes full id and SMS/MMS kind rather than id.toInt(); receiver queries (threadId,id,is_scheduled,is_mms) and legacy alarms reconcile without blind dispatch. Context.deleteScheduledMessage takes MessageKey.
- [ ] Run green: both classes, full gate/ratchet.
- [ ] Commit: fix: reconcile scheduled and multipart sends.

### Task 7: Carrier ingress, SIM and exported receiver

**Files:** Modify receivers/SmsReceiver.kt, SmsReceivedReceiver.kt, DirectReplyReceiver.kt, MmsReceiver.kt, services/HeadlessSmsSendService.kt, extensions/Context.kt, AndroidManifest.xml and transport/LocalMessageTransport.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/runtime/CarrierIngressTest.kt.
**Interfaces:** Produce durable provider source id before AgentSmsIntake; default-SMS-only agent beta posture; callback subscriptionId is an ID, never array index.
- [ ] Red: provider insert failure id=0 causes no agent intake; blocked arrival is recoverably recorded; wrong-SIM direct reply rejected; explicit spoof broadcast cannot make MmsReceiver read arbitrary file; legitimate MMS callback still accepted.
- [ ] Run red: emulator runner CarrierIngressTest; expect at least provider/source/SIM/spoof failures.
- [ ] Implement: durable ingress/filtered state, goAsync or worker lifetime, exact SIM routing, mmsto URI/error/stop, unique permissions-only part PendingIntents, source-key import/del consistency; constrain MMS export/caller after mmslib callback validation.
- [ ] Run green: same class and SMS role qualification, full gate/ratchet.
- [ ] Commit: fix: close carrier and receiver integrity gaps.

### Task 8: Typed shell routes and refresh lifecycle

**Files:** Create inkpilot/ui/StudioRoute.kt and StudioNavigator.kt; modify StudioHomeActivity.kt, SmsRefreshObserver.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/ui/StudioRouteTest.kt.
**Interfaces:** Produce sealed StudioRoute { Tab(tab: StudioTab), Thread(threadId: Long), Compose(recipients: List<String>, body: String, mediaUris: List<Uri>), Draft(draftId: String), Settings(section: String), Soul, Model, Auxiliary(key: String) }; StudioNavigator.open(route: StudioRoute), pop(): Boolean, save(outState: Bundle).
- [ ] Red: tab->thread->Back retains selected tab/scroll/unsent state after rotation; second SMS URI observer registration failure unregisters first.
- [ ] Run red: emulator runner StudioRouteTest; expect missing typed route and observer leak.
- [ ] Implement: saved per-tab navigation, persistent frame, lifecycle-safe observer registration, incremental canvas updates.
- [ ] Run green: same class, full gate/ratchet.
- [ ] Commit: feat: add persistent typed inkPilot shell.

### Task 9: Paged in-shell inbox and read-only thread

**Files:** Create inkpilot/ui/ConversationGateway.kt, InboxAdapter.kt, ThreadAdapter.kt; modify inkpilot/ui/MessagePanel.kt and StudioHomeActivity.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/InShellThreadTest.kt.
**Interfaces:** Consume StudioRoute and MessageKey; produce ConversationGateway.pageThreads(after: ThreadCursor?, limit: Int): Page<Conversation> and pageMessages(threadId: Long, after: MessageCursor?, limit: Int): Page<Message>, with stable row callbacks and explicit cursor types in this file.
- [ ] Red: 151st conversation is reachable; opening a thread shows shell footer and correct MMS/SMS bubbles after refresh/rotation, without starting legacy ThreadActivity.
- [ ] Run red: emulator runner InShellThreadTest; expect old 150 cap/legacy activity.
- [ ] Implement: RecyclerView paged inbox/thread; extract provider reads/mark-read/attachments behind gateway, no SimpleActivity cast.
- [ ] Run green: same class, full gate/ratchet.
- [ ] Commit: feat: render messages inside inkPilot shell.

### Task 10: In-shell SMS/MMS composer and inline held draft

**Files:** Create inkpilot/ui/ThreadComposer.kt and InlineDraftReview.kt; modify DraftPanel.kt, DraftReview.kt and StudioHomeActivity.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/InShellComposerTest.kt.
**Interfaces:** Consume MessageDispatcher.submit and AgentRuntime.approveAndSend(draftId,body,subscriptionId); produce ThreadComposerState(recipient,text,mediaUris,subscriptionId) persisted per thread.
- [ ] Red: manual SMS/MMS with attachment and held draft review retain footer; failed send/rotation/restart preserves text/media; staff edit/explicit SIM and approval yields visible outgoing row, no auto-send.
- [ ] Run red: emulator runner InShellComposerTest; expect legacy new compose/detached draft or lost media.
- [ ] Implement: host-neutral composer, inline held card, attachment grants, status and deliberate approval; preserve manual MMS parity.
- [ ] Run green: same class, full gate/ratchet.
- [ ] Commit: feat: own compose and draft review in thread.

### Task 11: Locked compatibility ingress and all auxiliary routes

**Files:** Create inkpilot/ui/ExternalRouteParser.kt and CompatibilityIngressActivity.kt; modify AndroidManifest.xml, NotificationHelper.kt, ShortcutHelper.kt, transport/HeartbeatNotification.kt, activities/MainActivity.kt, NewConversationActivity.kt, ThreadActivity.kt, SettingsActivity.kt and extensions/Activity.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/ShellIngressTest.kt.
**Interfaces:** Produce ExternalRouteParser.parse(intent: Intent): StudioRoute? with recipient and URI grants; consume StudioNavigator/open and StudioAppLock.
- [ ] Red: cold locked notification/share/Up, ACTION_SENDTO/VIEW/SEND/SEND_MULTIPLE, APPLICATION_PREFERENCES, archive/recycle/details and issued old PendingIntent never render legacy content or lose URI/recipient; direct reply uses exact SIM.
- [ ] Run red: emulator runner ShellIngressTest; expect inherited activity/app-lock/URI failures.
- [ ] Implement: exported legacy Main/Archive/Recycle/NewConversation/Settings and parentActivityName Up routes become locked, nonvisual shims or export-disabled; host owns auxiliary settings/search/archive/recycle/details/forward. Unique PendingIntent URI per thread; preserve grants.
- [ ] Run green: same class, full gate/ratchet and UI hierarchy screenshot gate.
- [ ] Commit: feat: route every messenger entry into inkPilot.

### Task 12: Studio v1->v2 held revision and inbox recovery

**Files:** Modify inkpilot/data/StudioDatabase.kt, StudioInbound.kt, StudioDrafts.kt, StudioSendParts.kt, runtime/AgentRuntime.kt, LocalDraftWorker.kt, SmsIntakeRecovery.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/StudioFreshnessTest.kt and runtime/SmsIntakeRecoveryTest.kt.
**Interfaces:** Produce Studio schema v2 held generation, durable recovery cursor/cancel generation; StudioRepository.regenerate(eventId: String, expectedRevision: Long): DraftRegenerationOutcome (Held, Stale, AlreadyClaimed); retryPending never replays a claimed send.
- [ ] Red: memory/Soul edit after held permits explicit new revision without overwriting receipt; recovery processes 201+ rows via continuation; cancellation/worker failure/restart cannot strand events or auto-send.
- [ ] Run red: emulator runner StudioFreshnessTest and SmsIntakeRecoveryTest with synthetic provider fixtures only; expect stale-trap/200-row failure.
- [ ] Implement: transactional v1->v2 migration, revisioned unique key/history and tombstones, bounded pages/reschedule, retry/backoff and all-part reconciliation.
- [ ] Run green: both classes and StudioSendingTest/StudioSendPartsTest, full gate/ratchet.
- [ ] Commit: fix: recover and regenerate held drafts safely.

### Task 13: Studio v2->v3 contact identity and sourced memory

**Files:** Modify inkpilot/data/StudioDatabase.kt, StudioContacts.kt, StudioRepository.kt, inkpilot/ui/ContactPanel.kt, App.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/StudioContactIdentityTest.kt.
**Interfaces:** Produce Studio schema v3 contact_aliases(address,region,contactId,reviewState) and memory_entries(contactId,source,reviewState,body); ContactResolver.resolve(address,region): ContactMatch (Unique|Ambiguous|Missing).
- [ ] Red: 10-digit/+1 ambiguity never blindly merges; memory+pause edit is atomic; READ_CONTACTS granted after startup starts contact-cache observer; revoke stops it.
- [ ] Run red: emulator runner StudioContactIdentityTest; expect duplicate/partial save/observer failure.
- [ ] Implement: transactional v2->v3 schema/copy, identity review UI, source/correct/forget/export memory, post-grant observer lifecycle.
- [ ] Run green: same class, StudioPersistenceTest, full gate/ratchet.
- [ ] Commit: feat: unify customer identity and memory.

### Task 14: Link MMS originals to Studio records

**Files:** Modify inkpilot/data/StudioDatabase.kt, studio/StudioFeatureSchema.kt, StudioAlbumStore.kt, StudioImageFiles.kt, StudioAlbumsPanel.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/studio/StudioImageLinksTest.kt.
**Interfaces:** Produce Studio v3->v4 image_links(imageId,contactId,projectId,staffId,appointmentId,sourceMessageKey) and importMmsOriginal(uri,link): ImageId.
- [ ] Red: import MMS to Client References preserves original once, links contact/project/artist/appointment, removes membership without deleting original, survives restart/URI grant.
- [ ] Run red: emulator runner StudioImageLinksTest; expect missing link/import.
- [ ] Implement: transactional v3->v4 schema and URI copy/metadata; relevant thread/contact opens linked album, paged gallery.
- [ ] Run green: same class and StudioAlbumStorageTest, full gate/ratchet.
- [ ] Commit: feat: link client reference originals.

### Task 15: Shared artist calendar and reminders

**Files:** Modify inkpilot/data/StudioDatabase.kt, studio/StudioFeatureSchema.kt, StudioCalendarStore.kt, CalendarEditor.kt, StudioCalendarPanel.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/studio/StudioAvailabilityTest.kt.
**Interfaces:** Produce Studio v4->v5 hours/time_off/reminders plus contact/project links; CalendarAvailability.propose(contactId,staffId,room,start,end,zone): Conflict|Available.
- [ ] Red: two unrelated tasks with no room do not conflict, same artist/shared room do; time off/DST ambiguity/subminute duration fail; edit/reschedule and reminder survive restart.
- [ ] Run red: emulator runner StudioAvailabilityTest; expect old default room/manual-only failures.
- [ ] Implement: transactional v4->v5 migration, staff hours/time off, conflict checks, local reminders, status/edit and same calendar for staff/agent.
- [ ] Run green: same class and StudioCalendarStorageTest, full gate/ratchet.
- [ ] Commit: feat: make local studio calendar authoritative.

### Task 16: Probe LFM3 tool protocol before registry design

**Files:** Create local-inference/src/test/kotlin/inkpilot/inference/ToolProtocolProbeTest.kt, app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/ToolProtocolDeviceTest.kt and scripts/inkpilot-tool-probe.py; modify runtime/ModelFiles.kt.
**Interfaces:** Produce ToolProtocolProbe.probe(modelHash,cases): ProbeReport(rawValidity,schemaValidity,latency,failures); Task 17 consumes verdict, not model-emitted arbitrary code.
- Baseline evidence: one host-only pinned LFM3 QAD case emitted a parseable read-only <tool_call> in about 9 seconds; the production runner still omits tools and executes none. This proves one syntax case, not registry reliability.
- [ ] Red: unsupported GGUF architecture/template is rejected on import; synthetic tool cases record malformed JSON, hallucinated tool and prompt-injection output instead of silently accepting.
- [ ] Run red: ./gradlew :local-inference:testDebugUnitTest, then emulator runner ToolProtocolDeviceTest; expect missing probe/weak magic-only acceptance.
- [ ] Implement: metadata/template validator and bounded synthetic probe with explicit checksum/settings; inventory installed variants, no silent downloads.
- [ ] Run green: same tests and a non-phone CPU probe, full gate/ratchet; save redacted report outside repo until reviewed.
- [ ] Commit: test: qualify LFM3 tool protocol before enabling actions.

### Task 17: Versioned read-only local tool registry

**Files:** Create agent-core/src/main/kotlin/inkpilot/agent/LocalToolRegistry.kt and ToolTypes.kt; modify inkpilot/runtime/AgentRuntime.kt and DraftPromptEncoding.kt; Test agent-core/src/test/kotlin/inkpilot/agent/LocalToolRegistryTest.kt.
**Interfaces:** Produce ToolDescriptor(name,version,inputSchema,scope), LocalToolRegistry.read(request: ScopedToolRequest): ToolResult for contact, bounded thread, availability and album references; no write tool.
- [ ] Red: forged contact/cross-thread scope and unknown tool/version reject; valid reads return bounded source-tagged data; MMS image marked unseen.
- [ ] Run red: ./gradlew :agent-core:test --tests inkpilot.agent.LocalToolRegistryTest; expect missing registry.
- [ ] Implement: typed descriptors, host read ports over StudioRepository, strict bounds and prompt encoding; no second data authority/network.
- [ ] Run green: same test, full gate/ratchet.
- [ ] Commit: feat: add scoped local read tools.

### Task 18: Durable proposal tools and checked staff actions

**Files:** Create agent-core/src/main/kotlin/inkpilot/agent/ToolProposals.kt; modify inkpilot/data/StudioDatabase.kt, StudioRepository.kt, runtime/AgentRuntime.kt, inkpilot/ui/AgentPanel.kt; Test agent-core/src/test/kotlin/inkpilot/agent/ToolProposalTest.kt and app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/StudioProposalTest.kt.
**Interfaces:** Produce Studio v5->v6 action_proposals(id,kind,contactId,sourceRevision,payload,state,receipt); proposeAppointment/proposeMemoryCorrection/draftReply return HELD; confirm(id,staffId) rechecks revision/conflict. SMS remains CheckedSmsSender.approveAndSend with explicit SIM.
- [ ] Red: malformed JSON, forged recipient, stale revision, duplicate confirmation, conflicting room, prompt injection and restart never mutate booking/memory or send; valid proposal waits for staff.
- [ ] Run red: ./gradlew :agent-core:test --tests inkpilot.agent.ToolProposalTest and emulator runner StudioProposalTest; expect missing checked proposal.
- [ ] Implement: strict parser/validator, transactional v5->v6 migration and receipts, staff review in side card/thread.
- [ ] Run green: both tests, full gate/ratchet.
- [ ] Commit: feat: hold local agent actions for staff.

### Task 19: Benchmark available LFM3 family and document breaks

**Files:** Create scripts/inkpilot-model-benchmark.sh and app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/ModelQualificationTest.kt; modify docs/DEVICE-ACCEPTANCE.md and runtime/AgentPanel.kt.
**Interfaces:** Consume ProbeReport and held-draft runtime; produce ModelQualificationReport(hash,variant,caseResults,p50,p95,peakRss,thermal,unsafeCount).
- [ ] Red: benchmark fixture flags wrong recipient, unsupported policy promise, cross-contact leak, invalid tool and truncation as failure; missing weights are UNTESTED, not PASS.
- [ ] Run red: emulator runner ModelQualificationTest with synthetic cases; expect missing report or incorrect result.
- [ ] Implement: smoke each available variant, then >=100 adjudicated synthetic studio cases per survivor; offline OnePlus >=20 consecutive turns, staff score and redacted generation/queue-to-held latency, RSS/battery/thermal. Current QAD baseline is ~25–32s/reply and ~1.14 GB peak, but a 120s inferenceLock wait plus fresh 120s JNI deadline can yield ~240s terminal latency; prompt capacity <=1,792 tokens after 256 reserve. Provisional beta bar >=90% acceptable routine drafts, <=60s P95 queue-to-held on target 8 GB, zero unsafe actions; surface queued/stopped/retry state and document 4 GB separately.
- [ ] Run green: same test, full gate/ratchet and physical benchmark; document exact failed cases/unsupported variant.
- [ ] Commit: test: record LFM3 qualification and safe fallbacks.

### Task 20: Native manual Phone role with reversible test

**Files:** Create inkpilot/phone/StudioInCallService.kt, PhoneRoleSetup.kt, PhonePanel.kt; modify AndroidManifest.xml and inkpilot/ui/MessagePanel.kt; Test app/src/androidTest/kotlin/org/fossify/messages/inkpilot/phone/PhoneRoleQualificationTest.kt.
**Interfaces:** Produce ACTION_DIAL handler and full InCallService incoming/ongoing controls; phone tab owns dial/call log/contact route. No AI audio API claimed.
- [ ] Red: dialer role qualification rejects current ACTION_DIAL-only app; mock incoming/ongoing/emergency/fallback UI verifies no caller content before unlock.
- [ ] Run red: emulator runner PhoneRoleQualificationTest; expect missing InCallService/role.
- [ ] Implement: complete manual dialer and in-call UI, permissions and TelecomManager call path. On OnePlus, switch from current OnePlus Dialer only in controlled test; restore it on failed gate.
- [ ] Run green: same test/full gate plus real inbound/outbound human calls with second handset, explicit emergency mock only.
- [ ] Commit: feat: provide native manual Phone.

### Task 21: Provider-backed optional bridge, off by default

**Files:** Modify transport/README.md, app/.../transport/OutboundWorkSender.kt, OutboundPickupSchedule.kt, OutboundSendProgressStorage.kt, LocalMessageTransport.kt, PairingExchangeBinding.kt and res/xml/network_security_config.xml; Test app/src/androidTest/kotlin/org/fossify/messages/transport/BridgeHistoryTest.kt and transport/src/test/.../OutboundPickupWireTest.kt.
**Interfaces:** Produce provider-backed bridge outbound receipt visible to ConversationGateway and an opt-in configuration; bridge is off by default in local beta and remains a separate module for a later OpenFang adapter.
- [ ] Red: bridge-originated SMS absent from native thread, replayed pickup can duplicate, 15-minute pickup cannot claim live reply, secret toString/IPv6 mismatch.
- [ ] Run red: emulator runner BridgeHistoryTest and ./gradlew :transport:test; expect invisible row/contract failures.
- [ ] Implement: safe provider reconciliation/idempotent source key/per-part verdict; keep bridge disabled by default, label 15-minute periodic pickup as delayed rather than live, redact secrets and align loopback policy.
- [ ] Run green: same tests, full gate/ratchet; no invisible agent SMS when enabled.
- [ ] Commit: fix: make optional bridge behavior honest.

### Task 22: Release privacy, accessibility and full OnePlus gate

**Files:** Modify .github/workflows/release.yml, .github/workflows/inkpilot-build.yml, fastlane/Appfile, app/src/main/res/values/strings.xml, translated FAQ files, layout_thread_send_message_holder.xml, app/lint-baseline.xml, detekt-baseline.xml, docs/ARCHITECTURE.md, docs/DEVICE-ACCEPTANCE.md and agent-core/README.md; Create scripts/verify_inkpilot_release.py and app/src/androidTest/kotlin/org/fossify/messages/inkpilot/smoke/OneShellAcceptanceTest.kt.
**Interfaces:** Produce release verifier checking package/store identity, privacy copy, no inherited UI/role regression and audit evidence manifest; no cloud requirement.
- [ ] Red: release verifier rejects Fossify package/listing/mutable shared workflow, MMS-only Internet claim and stale core README; acceptance test fails on any legacy route, 1.5x cramped labels or landscape footer clipping.
- [ ] Run red: python3 scripts/verify_inkpilot_release.py and emulator runner OneShellAcceptanceTest; expect inherited identity/UI failures.
- [ ] Implement: own pinned release workflow, corrected localized network FAQ, relevant lint/Detekt baseline retirement, persistent labels/TalkBack/contrast/icon semantics, export/restore, truthful docs.
- [ ] Run green: verifier, module/debug gate, emulator device workflow, lint/Detekt and minified/release build; ratchet. Then exclusive OnePlus walk with second handset: SMS+MMS inbound -> canonical contact -> local held reply -> staff edit/explicit SIM -> receiving-end delivery and visible thread -> reference -> artist -> appointment/reminder; test failure/UNKNOWN, restart, 201+ fixture on emulator only, notification/share/lock and no auto-send. Record redacted screenshots/logs/APK hash.
- [ ] Commit: release: qualify complete local text beta.

## Coverage and voice gate

Audit IDs: UI-01–10 -> Tasks 2, 8–11, 13, 22; MSG-01–12 plus first-launch deletion -> Tasks 1, 3–7; AG-01–06 -> Tasks 12, 16–19; DATA-01–05 -> Tasks 13–15; PHONE-01 -> Task 20; BR-01–04 -> Task 21; QA-01–05 -> Tasks 2, 7, 19, 22. Each task's review checks the audit row and its test, not just a passing build.

Cellular AI answering is not Task 20's success condition. After manual Phone works, a separate feasibility experiment must prove call audio ingress and egress on the target OnePlus build. Android public docs describe ROLE_DIALER/InCallService UI/control and restrict ordinary third-party cellular voice capture; if the device lacks privileged audio, choose and prove a system app/OS image, external modem or VoIP path before implementing STT/TTS/model loop. Record a blocked result honestly; never claim 100% voice from a dialer UI.
