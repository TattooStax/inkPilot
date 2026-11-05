# App smoke and composer test function ledger

Pinned to integrated commit `cacf2ae2` (2026-11-05), with proposed
cleanup changes in isolated commits `fd1a374a` and `9040397e`. I read
all 22 Kotlin files under `app/src/androidTest/.../inkpilot/smoke`, both
`.../messages/messaging` test files, all three Java share-fixture files,
and the test manifest end to end. These contain 23 test classes and 72
`@Test` methods. Helper files are `SmokeViews.kt`,
`RevocablePreviewProvider.kt`, `CrossPackageShareProvider.java`,
`CrossPackageShareSender.java`, and `CrossPackageShareCleanupReceiver.java`.
Each method below maps its concrete assertion to a production seam and its
fixture or cleanup. Static coverage does not imply hosted execution.

## Synthetic dispatch, composer, and media tests

`smoke/DispatchOutcomeTest.kt` (5). Fixed synthetic request and transport
callback, no radio; each persisted request is cleared in `finally`:

- `unreadableUriNeverReachesTransportAndDraftSurvives`: unreadable URI makes `MessageDispatcher` reject before callback and `ComposerStore.complete` preserves exact request.
- `expiredOrMissingUriAfterReopenNeverFallsBackToText`: reopen store, try missing `content://` URI, reject without transport and retain text/URI.
- `deniedPermissionDoesNotSend`: injected permission refusal rejects before callback and preserves draft.
- `transportThrowIsUnknownAndExactRequestIsForwarded`: callback sees exact request then throws; dispatcher returns Unknown and store retains it.
- `onlyAcceptanceClearsDurableComposer`: accepted completion removes persisted request.

`smoke/ComposerClaimRecoveryTest.kt` (2). Random draft IDs, synthetic
dispatcher, cleanup clears claim/pending state:

- `secondMarkerFailureCannotStrandAnInvisibleUnretryableDraft`: inject second durable marker write failure; reopened store still shows Unknown, blocks edits/retry and never reaches handoff.
- `verifiedPretransportRejectionStillAllowsRetry`: injected permission denial removes claim marker, keeps request and permits retry without handoff.

`smoke/InShellComposerTest.kt` (9). Production `ThreadComposer`,
`prepareInShellComposerSend`, `ComposerStore`, and injected transport;
no native send. Five methods originally cleared drafts only after success;
`fd1a374a` makes their cleanup unconditional and resets the fixed preclaim ID:

- `missingSmsRoleRefusesBeforeSimLookupOrComposerClaim`: forced role false rejects before SIM callback or durable claim; fixed ID cleared in `finally`.
- `groupSmsIsVisibleRefusalBeforeSimOrComposerClaim`: group MMS disabled in config, preflight rejects before SIM; config and draft restored in `finally`.
- `composeKeepsShellAndOriginalTextOnRotation`: shell route, edited body, synthetic media URI survive recreation; draft cleanup now in `finally`.
- `failedShareStaysVisibleAndCannotSendUntilAttachmentRemoved`: `shareCopyFailed` route keeps caption and disables Send, removal restores Send; draft cleanup in `finally`.
- `failedMmsRetainsOriginalAcrossStoreRecreation`: injected unreadable media rejects, reopened draft equals original; fixed ID cleanup now in `finally`.
- `unknownDoesNotRetryOrDowngradeToText`: injected ambiguous handoff is called once, retained media blocks second dispatch; fixed ID cleanup now in `finally`.
- `distinctComposeRoutesNeverRestoreAnotherRecipientOrMedia`: two route IDs survive recreation without cross-contact restore; both IDs now cleared in `finally`.
- `concurrentHostsClaimOneCarrierHandoffEvenWhenAccepted`: two synchronized store callers yield one synthetic Accepted and one Rejected; random ID cleared in `finally`.
- `preclaimExceptionAllowsRetryButDurableUnknownDoesNot`: preclaim exception remains retryable; postclaim Unknown blocks retry; fixed ID reset and cleanup now unconditional.

`smoke/MmsComposerOutcomeTest.kt` (7). Synthetic callback/store state,
random composer IDs and `finally` cleanup; no native provider mutation:

- `revokedSimAccessBeforeClaimRetainsEditableDraft`: SIM lookup null or throws; both reject before claim and leave editable store.
- `acceptsOnlyCanonicalNativeMmsCallbackRows`: `isMmsCallbackRow/mmsCallbackRowBound` accepts canonical row URI and rejects part/SMS/mismatched ID.
- `onlyMatchingConfirmedAttemptCanUnlockAnUnknownMms`: mismatched callback cannot complete; exact callback clears pending state, prevents replay.
- `pendingComposerRendersReadOnlyUntilExactCallback`: pending claim disables editor/Send; matching confirmation prunes route and text.
- `hiddenCompletedComposeCannotReseedSentText`: callback on hidden compose clears it before returning to SMS.
- `callbackWhileObserverStoppedPrunesRouteBeforeResumeRender`: confirmation during CREATED lifecycle prunes compose on resume.
- `confirmedCallbackSurvivesDeathBetweenJournalAndCleanup`: constructor recovery after directly persisted confirmation marker removes pending state. Constructor reopening simulates recovery; no process death.

`smoke/ShellComposerMediaRestartTest.kt` (2). The shell script runs these
exact classes around `adb shell am force-stop` on a disposable emulator;
test provider owns a private, revocable image. Verify phase cleans prefs,
provider file and composer in `finally`:

- `commitReadableMediaAndVisibleShellComposer`: grant valid PNG read, persist exact recipient/body/URI, open current `StudioHomeActivity` composer and persist target PID.
- `revokedMediaAfterProcessDeathCannotSendTextAlone`: assert different PID and same store/visible fields; revoke read while MIME remains PNG, click Send, see unreadable-media rejection and no claim/handoff.

`smoke/ComposerShareFailureIngressTest.kt` (1):

- `unsupportedShareKeepsCaptionVisibleAndBlocksSendUntilReattach`: share unsupported text file via old alias, wait for `StudioHomeActivity` route, assert caption persists and Send disabled; `finally` finishes activity, clears composer and source file. This is a real ingress alias, still synthetic media and no carrier.

`messaging/ComposerSharedMediaTest.kt` (8). Random source files and IDs,
`FileProvider`, private `composer-shares` directory; most methods use
`finally` cleanup. Methods that call `recoverSharedComposerImages` simulate
a later session inside the same process:

- `interruptedCopyBeforeComposerCommitIsReapedOnNextProcess`: capture uncommitted copy, simulate recovery, assert file/journal removal; source cleanup only, so a failed assertion can leave a private test copy.
- `committedCopySurvivesPriorProcessJournalRecovery`: synthesize prior-process journal, recover, assert committed copy and URI survive; store/source cleanup.
- `canceledShareRemovesCopyAndItsRecoveryMarker`: discard captured copy and assert journal/copy gone, original source intact.
- `nextSharePrunesCrashedPartialButKeepsFinishedCopy`: synthetic `.part` orphan is reaped on next capture while two finished copies remain; all copies and source cleaned.
- `oversizedShareLeavesNoPrivateCopy`: 1 MiB+1 source rejects capture and directory entries unchanged; source cleaned.
- `removalAndClearDeleteOnlyOwnedComposerCopy`: replacing attachment then clearing draft deletes owned copies but leaves original and foreign provider URI alone.
- `confirmedMmsReleasesCopyOnlyAfterExactCallback`: wrong callback keeps copy, exact attempt removes it; carrier not invoked.
- `interruptedConfirmedMmsJournalRecoversAndReleasesCopy`: direct confirmation marker plus new `ComposerStore` recovers and removes copy; same-process constructor recovery.

`messaging/ComposerDiscardRecoveryTest.kt` (4). Private copy and journal
are created from a random source. Each test closes via `finally`;
recovery is simulated by constructing a second store in the same process:

- `interruptedAcceptedBeforeCompletedMarkerCleansCopyWithoutReplaying`: terminal discard marker before completed flag cleans file, leaves Unknown and rejects synthetic retry.
- `interruptedAcceptedAfterCompletedMarkerCleansCopyWithoutReplaying`: same with completed flag committed before constructor recovery.
- `interruptedClearCommitReapsTheUnreferencedPrivateCopy`: remove composer pref after discard journal; recovery deletes orphan and marker.
- `interruptedReplacementKeepsReferencedCopyUntilTheCommit`: marked copy remains while referenced, then deleting URI from pref causes later recovery to remove it.

## Shell, routing, and UI regression tests

`smoke/ShellBaselineTest.kt` (1):

- `shellOwnsThreadAndNewMessageWithoutLaunchingInheritedActivities`: `@Before` requires pristine SMS/MMS provider and app DB plus emulator role/permissions. Inserts one synthetic native SMS, navigates inbox, thread, forward and compose in shell; monitors legacy activities for zero launches; `@After` removes owned provider, cache and composer rows. A failure can make subsequent classes' app state nonpristine, though the fixture teardown attempts cleanup.

`smoke/InShellThreadTest.kt` (2):

- `repeatedRotationWaitsForAllPreviouslyLoadedPages`: in-memory `StudioCanvasState/StudioPagedList` loads pages through two saved states, counts load requests exactly.
- `reaches151stThreadAndKeepsMixedThreadInsideShellAcrossRotation`: emulator/SMS-role `Assume` guard; inserts 151 native SMS threads plus MMS part, pages to 151st UI row, checks mixed messages, rotation, provider observer, legacy activity monitor, read status; `finally` removes created SMS/MMS URIs. A fixed one-second observer sleep is timing-sensitive, and the MMS part relies on parent-row cascade.

`smoke/ThreadReviewRegressionTest.kt` (7). Fake `StudioConversations`
gateways and in-memory widgets, no carrier:

- `providerFailureCanRetryTheSameThreadPage`: first page throws, retry starts from null cursor and yields one row.
- `providerFailureCanRetryTheSameInboxPage`: READ_SMS `Assume` guard, first inbox page throws, retry adds one row.
- `olderTypedAnchorAndOffsetSurviveInsertionAndTwoRotations`: manually laid-out `StudioPagedList` preserves typed anchor and pixel offset after inserted rows and two saved states.
- `hiddenThreadsAreInvalidatedWithoutDroppingOtherPanels`: `StudioCanvasState` invalidates two thread views but retains phone view.
- `mmsImageBubbleOpensShellAttachmentRoute`: bound MMS image row labels and click callback yield exact attachment and encoded route; no actual external activity is opened.
- `selectedHeldDraftLeadsReviewWhenNewerDraftSharesThread`: pure `prioritizeThreadDrafts` ordering with selected older ID.
- `compactFocusedReviewShowsReplyAndKeepsSimActionReachable`: injected draft footer on 320×400 canvas keeps editor, SIM action and conversation viewport visible.

`smoke/FocusedReviewRouteTest.kt` (1):

- `selectedHeldReplyAndSimAreVisibleBeforeAnyRegularComposer`: default Studio DB receives random contact/event and held draft; real async `StudioRoute.Draft` shows historical text and disabled approval/SIM, no ordinary composer. Transactional `finally` deletes related rows and restores prior drafting flag; manual FK delete ordering is a future schema risk.

`smoke/ShellIngressTest.kt` (5):

- `shareAndSmsIntentsKeepSeparateRecipientsBodyAndGrantedMedia`: `ExternalRouteParser` maps two image URIs, grant flag, SENDTO body/recipients and legacy extras to separate route IDs; no activity launch.
- `oldExplicitComponentsResolveToSecureIngressTargets`: manifest alias targets and parsed old intents resolve to `CompatibilityIngressActivity` family and shell routes.
- `historicalPendingIntentAndPreferencesResolveIntoShell`: emulator guard; launches historical pending intent, settings, archive, recycle, details, vCard and launcher aliases and checks titles/Back. The pinned source lacks an outer `finally` on intermediate failure; isolated `9040397e` finishes launched shell/ingress activities and cancels the test PendingIntent.
- `coldShareRetainsPrivateImageAfterSenderAndTaskClose`: real cross-UID test APK provider grants a PNG into compatibility ingress; local copy survives sender cleanup and Activity finish. `finally` clears owned composer, but sender source cleanup is only in success path.
- `lockedOldNotificationNeverInflatesLegacyOrPrivateShellContent`: emulator/fresh-lock `Assume` guard; synthetic PIN locks shell, old notification and delayed share remain secure/blank; `finally` resets credential and finishes tracked ingress/unlock Activities.

`smoke/OneShellAcceptanceTest.kt` (6). Real shell UI on disposable
emulator; `ActivityScenario` closes hosts, monitors removed in `finally`:

- `newMessageAndSettingsStayInsideFiveTabShell`: navigate compose and side settings; footer and legacy-activity monitor checks.
- `sideCardHasNoDailySelectionAndPhoneTabReturnsToPhoneCanvas`: soul side route clears daily selection; Phone tab restores Phone canvas.
- `landscapeSideCardScrollsAndPhysicalSoulTapWorks`: 1.5× font, landscape rotation, physical `input tap`, soul route, scroll to Close. `atLargeText` restores global font scale in `finally`; coordinate-based tap and accessibility state remain device-sensitive.
- `calendarReminderHintOpensCalendarCanvas`: launch calendar hint, assert selected tab and shell title.
- `heldReplyEditorKeepsVisibleProgrammaticLabel`: construct synthetic `InlineDraftReview`; verify labelFor/editor/hint, without database or carrier.
- `tabsRemainVisibleAtLargeTextAndLandscape`: traverse five tabs at 1.5× and landscape; checks selection, clipping, accessibility target, contrast and text layout. Host CI has observed intermittent Studio tab transition failures in an exact repeat; this assertion is a real signal, root cause not proven by this source alone.

`smoke/HomeNavigationSmokeTest.kt` (1):

- `dailyTabsRenderWithoutSendingOrCalling`: five canvas anchors via accessibility and screenshots; Contacts/Studio content assumes preceding synthetic local-loop state. Screenshot artifacts are synthetic emulator-only.

## Model gate and long device proof

`smoke/ModelQualificationTest.kt` (2):

- `unsafeOutputsAreFlagsNotModelSuccesses`: synthetic model text cases must produce policy flags for booking/date/price/image/medical/cross-contact/tool/language/truncation/overlong/duration failures.
- `harmlessHeldTextCannotAuthorizeAnAction`: constructed safe language examples yield no flags. Neither test invokes a GGUF model; these are policy examples, not a benchmark.

`smoke/ToolProtocolDeviceTest.kt` (1):

- `crossContactToolNeverQualifies`: injected JSON cross-contact tool case yields `CROSS_SCOPE` and disables execution. Parser-boundary fixture only, no model/provider action.

`smoke/LocalLoopSmokeTest.kt` (3). `inkpilot-device-run.sh` supplies a
checksum-pinned installed GGUF, SMS role, synthetic inbound SMS and explicit
method sequence. This current test intentionally does **not** invoke it:

- `prepareForSyntheticSms`: refuse nonempty contact/draft/provider outbox, set AgentSettings disabled and fixture Soul; no cleanup because later phases reuse state.
- `incomingSmsNeedsStaffWhileModelIsUnqualified`: observe native inbound, save memory, simulate stale ON bit, assert `AgentRuntime.processPending` makes zero drafts, closes drafting, records MODEL_UNQUALIFIED attention and zero outgoing; stores synthetic JSON/screenshot for restart.
- `blockedUnqualifiedStateSurvivesProcessRestart`: after script force-stop, read JSON and assert disabled policy, no held draft, stable attention and zero send.

`smoke/WorkerWakeupSmokeTest.kt` (1):

- `incomingSmsStaysWithStaffWhileModelIsUnqualified`: second synthetic inbound after launcher wakeup remains ordinary provider row, no held draft/attention or outbox send; writes bounded synthetic JSON. It depends on prior local-loop setting and has no cleanup because entire device job is disposable.

`smoke/NativeSendSmokeTest.kt` (1, **unselected at cacf2ae2**):

- `selectedEmulatorSimUsesProviderAndReplayGuard`: uses real emulator telephony, held draft and selected SIM; validates callback parts and provider reconciliation if an active SIM exists. If none exists, it writes `nativeSendAttempted=false` and can pass without a native send. It also expects prior `BLOCKED_UNSAFE_CONTENT` or `HELD`, while current device loop records `BLOCKED_UNQUALIFIED`; this method cannot currently prove native send and must not be labeled a passing carrier gate.

`smoke/BatchFailureSmokeTest.kt` (1, **unselected**):

- `oversizedNativeContextDoesNotBlockFollowingLocalReply`: requires prior native-send evidence, runs one production batch with 8,000-character memory and a following contact, checks first model failure and second held/unsafe outcome. Current qualification policy blocks RAW model; no CI selector invokes it.

`smoke/ReviewedScopeRuntimeSmokeTest.kt` (2, **unselected**):

- `productionWorkerBlocksScopedEventAndExplicitRegeneration`: creates queued and held reviewed events under inference lock, asserts reviewed scope becomes staff attention with no RAW inference or send; writes restart evidence. Model ON toggle conflicts with current unqualified guard, so it stays out of the current device loop.
- `blockedScopeSurvivesProcessRestart`: reads evidence after intended script force-stop, verifies no draft and stable attention/send count; no current two-phase selector.

## Test harness boundaries

`SmokeViews` polls accessibility for up to 20 seconds, not a fixed assertion
delay. `RevocablePreviewProvider` retains MIME while denying file read and
stores only a synthetic PNG in the test APK. `CrossPackageShareSender` and
its provider/cleanup receiver run under the separate test APK UID; the
caller checks UID difference. None of these fixtures contains a live phone
number. Raw model-loop logcat/screenshots are uploaded only from a QEMU
guarded disposable emulator; `run-inkpilot-test-class.sh` deletes raw
instrumentation output and publishes method/file-line verdicts.

Code-level limitations: image preflight checks MIME/readability/size, not
image decodability; the synthetic `MessageDispatcher` assertions do not
exercise an actual carrier; same-process `ComposerStore` construction is
not process death; unchecked cleanup after a failed assertion can taint
later exact-class results. The proposed composer cleanup change addresses
the five identified methods only.
