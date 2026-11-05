# inkPilot source audit delta — 2026-11-05

This supplements the [2026-11-04 line-read audit](INKPILOT-FULL-CODE-AUDIT-2026-11-04.md)
at `0b2d8fc08803a2aac029283266a71dfea27cac40`. The source snapshot here
is local combined tree `18fdb295f21cd508d03445293e9297fc724e7bf3`
(`e298c6d`), based on implementation `f50de99`. It is an inventory and
targeted source review, **not** a new line-by-line audit of every file or a
functional-beta verdict.

Between these snapshots, 508 tracked paths changed. Of these, 191 production
source files under `src/main` changed: 107 additions and 84 modifications
(`app` 171, `agent-core` 9, `local-inference` 6, `transport` 5).
The [machine-readable delta index](audit-index/delta-source-2026-11-05.jsonl)
lists each of the 191 files and 1,011 named declaration locators. Each row
records the changed owner or nearest preceding declaration and candidate
production/test files containing that name. The
[index generator](../scripts/audit_delta_index.py) makes the inventory
repeatable. Lexical references are search hints: overloaded names, lambdas,
manifest callbacks, reflection, native calls, and comments require manual
inspection. A zero-declaration row for `Constants.kt` represents file-level
constant changes. The index does not prove every call path or test assertion.

## Source paths and verification points

| Workflow | Source route and owner | Existing verification and remaining boundary |
| --- | --- | --- |
| External shell entry | `CompatibilityIngressActivity.onCreate` → `ExternalRouteParser.forward` → `StudioHomeActivity.onCreate/onNewIntent` → `StudioNavigator` and `ComposerStore`; notification intents use `studioPanelIntent`. | `ShellIngressTest`, `OneShellAcceptanceTest`, `NotificationEntryRouteTest`, `InShellComposerTest`, and `FocusedReviewRouteTest`. At published `0311decd`, the hosted build, Detekt and local loop passed and shell was 38/39: cold cross-package share did not yield a resumed Home with its exact URI. The later diagnostic tree in this report is still unqualified. |
| Native inbound to held reply | `SmsReceiver` / `SmsAdmission` → provider and `SmsIntakeRecovery` → `StudioRepository.recordInbound` → `AgentRuntime.processPendingForWorker` → `CustomerServiceDrafts` → held row → `InlineDraftReview`. | `CarrierIngressTest`, `SmsIntakeRecoveryTest`, `LocalLoopSmokeTest`, `BatchFailureSmokeTest`. Hosted pinned QAD generated an unsupported booking claim; the safety gate blocked it and retained staff attention. This is safety evidence, not useful-reply qualification. |
| Checked SMS and MMS | `InlineDraftReview` → `CheckedSmsSender` → `StudioSendClaims` / `StudioSendParts` → `SmsManager` callbacks and native provider; human compose uses `ThreadComposer` → `ComposerStore` / `MessageDispatcher` → SMS or MMS callbacks. | `NativeSendSmokeTest`, `SmsPartJournalTest`, `MmsComposerOutcomeTest`, `ComposerClaimRecoveryTest`. These are synthetic emulator/provider checks. Recipient delivery, real MMS and exact physical role behavior are unverified. |
| Scheduled sends | `ScheduledMessageReceiver.handleIntent` → `resolveScheduledAlarm` → `dispatchScheduled` → `ScheduledVerdicts.claimPrepared` and part callbacks. | `ScheduledIdentityLifecycleTest`, `ScheduledQueueTest`, `ScheduledVerdictTest`. A stale alarm can dispatch an edited row before its new due time because the claim reloads content but does not check the current due time. A repair is isolated, not in this tree. |
| Notification direct reply | `NotificationHelper` → `DirectReplyReceiver.onReceive` → `DirectReplyPendingStore.hold` → verified `sendMessageCompat`. | `CarrierIngressTest` covers held direct reply and headless admission. A missing/zero thread ID or throwing saved-SIM lookup returns before `hold` in the current receiver, dropping human input. An isolated repair requires Android tests. |
| Studio, calendar and phone | `StudioRepository` and `StudioFeatureSchema` hold contacts/albums/calendar; `PhonePanel` / `DialActivity` → role setup and `StudioInCallService` for manual calls. | `StudioContactIdentityTest`, `StudioCalendarUiTest`, `StudioImageLinksTest`, `ManualDialUiTest`, `PhoneRoleQualificationTest`, `CallNotificationActionsTest`. Manual call source exists, but no call audio capture, speech loop or AI answering path exists. No real Phone-role call matrix passed. |
| Optional bridge | `OutboundWorkSender.sendGuardedOutboundWork` → pinned `SmsManager` and result intent; independent from the checked native composer. | Pure transport tests and bridge-history source checks do not establish a native thread row for bridge work. Keep it outside the local-first beta until provider visibility and replay are proved. |

## Findings to carry into the plan

| Priority | Exact source evidence | Required gate |
| --- | --- | --- |
| Blocker | `ExternalRouteParser.forward` carries the shared `ClipData` grant, and `CompatibilityIngressActivity` forwards to Home, but the exact cold cross-package share still failed at `ShellIngressTest.coldShareForwardsReadableUriAndPrivateBodyIntoOneComposer` on `0311decd`. A monitor-only explanation was ruled out by a resumed-Home probe. | Inspect the bounded sender/alias/Home lifecycle diagnostic from tree `18fdb295`; fix the observed stage and rerun the same-source shell suite before calling one-shell ingress complete. |
| Blocker | `AgentRuntime.BOUNDED_INTENT_QUALIFIED=false` selects RAW rendering and no evidence reader; `localReadTools` constructs a registry but has no production caller. The latest QAD consultation was blocked after inference. | Keep scoped selector disabled; prove reviewed context, fixed corpus, blind staff scoring and consecutive offline phone replies. Never use a blocked result as useful-answer evidence. |
| High | `InlineDraftReview.view` writes `held_staff_edits` preferences, while `StudioArchiveExporter` exports Studio SQLite and original images only. Terminal send/dismiss does not make those preferences part of the archive. | Qualify v9 held-edit storage, exact approved body, migration and export on one source SHA. Export still needs a tested restore path. |
| High | `ScheduledDispatch.dispatchScheduled` reloads the edited message inside its claim but does not compare its persisted due time against the firing alarm. | Run the isolated due-time guard through Android/Detekt and a stale alarm/edit fixture; physical recovery remains separate. |
| High | `DirectReplyReceiver.onReceive` returns for missing thread ID and reads saved SIM before persisting `RemoteInput`. | Persist human text before fallible identity/SIM checks, then prove staff-visible review and no unintended send on device. |
| High | `VCardIngressActivity.destination` drops the historical card URI into the generic Contacts tab. External shared images remain temporary granted URIs until a private copy exists, and cleanup must not delete a live locked draft. | Test staff-confirmed vCard preview/import, cross-package URI access, bounded media copying and process-death ownership. Isolated fixes are not included in this snapshot. |
| Release | `StudioArchiveExporter` documents an export-only plaintext format and explicitly has no restore action. The optional bridge's direct `SmsManager` path has no proven native provider row. | Demonstrate restore before treating export as recovery; keep bridge disabled or prove provider-backed visibility and callback reconciliation. |

The current direction in [the plan](INKPILOT-PLAN.md) already recognizes the
RAW model, preference-held edits, scheduled/direct-reply gates, export limit
and physical acceptance gap. This delta adds the specific cold-share failure
stage and the direct-reply/stale-alarm source paths. The historical 40%
estimate remains tied to its older snapshot; this inventory does not create
a new beta percentage.
