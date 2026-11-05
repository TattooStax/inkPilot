# App JVM test function ledger

Pinned to integrated commit `cacf2ae2` (2026-11-05). All 18 files and all
61 `@Test` methods under `app/src/test` were read end to end. Every test here
uses constructed in-memory values, injected callbacks, or static functions;
none installs an app, touches a carrier/provider, or writes a persistent
fixture. Cleanup is therefore not applicable. Each line names the production
function or seam and the actual assertion. This is test-source coverage, not
proof that the Gradle task succeeded on this commit.

## Native provider, identity, and presentation

`extensions/NativeDeleteGateTest.kt` (3):

- `roleLossDoesNotTouchProviderOrCache`: `deleteProviderRowThenCache(false)` refuses both injected native and cache deletes.
- `zeroRowNativeDeleteLeavesCacheForReview`: native delete returns zero and readback still sees row; cache delete callback remains uncalled.
- `nativeDeleteNeedsExactProviderReadbackBeforeCacheRemoval`: row-present or unknown readback blocks cache deletion; verified absence permits one cache delete.

`extensions/NativeThreadReadStatusTest.kt` (3):

- `roleLossLeavesProviderAndLocalUnread`: `markThreadReadAfterNative(false)` calls neither provider nor local update.
- `deniedProviderUpdateLeavesLocalUnread`: injected `SecurityException` leaves local unread.
- `partialOrUnverifiableReadbackLeavesLocalUnread`: MMS unread/unknown blocks local mark even after native update; both SMS and MMS verified clear permit it once.

`extensions/ThreadSnippetTest.kt` (3):

- `newerMmsWinsOverOlderSms`: `latestThreadSnippet` compares MMS seconds with SMS milliseconds, returns MMS.
- `newerSmsWinsOverOlderMms`: reverse ordering returns SMS.
- `sameSecondLeavesMmsPreviewAndSmsOnlyStillWorks`: tie selects MMS; absent MMS selects SMS.

`inkpilot/ui/ConversationPagingTest.kt` (3):

- `sameTimestampUsesTypedOriginThenFullProviderId`: `messageBoundary` emits exact SMS/MMS SQL and full 64-bit MMS ID arguments.
- `smsBoundaryExcludesMmsAtSameTimestampButRetainsOlderMms`: MMS branch has older-time predicate and explicit same-time false; SMS arguments retain ID.
- `firstPageHasNoBoundaryOrArguments`: null cursor creates empty boundary for SMS and MMS.

`inkpilot/ui/MessagePresentationTest.kt` (3):

- `smsAndMmsDraftsNeverClaimSentOrDelivered`: `deliveryLabel` returns Draft for both namespaces.
- `sentSmsHonorsDeliveryReportWhileMmsUsesItsOwnBoxNamespace`: SMS status and MMS box cases map to Sent, Delivered, pending, failed, and Sending labels.
- `uncertainPartVerdictOverridesNativeSentAndStaleOutboxNeedsReview`: part uncertainty overrides native Sent; multipart last part is not full delivery; `staleUntrackedSmsOutcome` returns unknown only past its threshold in outbox.

`messaging/MessageKeyUnitTest.kt` (5):

- `equalNumericIdsHaveFourIndependentIdentities`: `Message.key/getStableId/getSelectionKey/areItemsTheSame` distinguish SMS/MMS and scheduled variants at equal ID.
- `uiNamespacesCannotAliasAcrossDifferentNumericIds`: cross-origin keys and stable IDs stay distinct across zero, 32-bit boundary and maximum IDs.
- `contentChangesPreserveIdentityAcrossReloads`: changed body/date/read keeps key but changes content equality; changed ID changes item identity.
- `fullLongIdIsRetainedInTypedIdentity`: all five signed/large IDs across four origins survive copies and remain unique.
- `stableIdsSeparateAdjacentRowsAndSelectionUsesHighIdBits`: 404 adjacent origin/ID combinations have unique stable IDs; high ID bits alter selection key.

## Carrier admission, callback, and SIM policy

`inkpilot/runtime/CarrierIngressPolicyTest.kt` (5):

- `nativeCommitMustHaveBothProviderAndThreadIdentity`: `nativeSource` rejects zero/negative ID or missing thread and emits `sms:42` for valid inputs.
- `subscriptionsAreIdsNeverPositionsAndAmbiguityFailsClosed`: exact subscription ID selected; index and ambiguous/default missing selections refused; one active SIM is allowed.
- `mmsCallbacksOnlyResolveOnePositiveNativeProviderRow`: `mmsProviderId` accepts two canonical MMS paths, rejects file/media/part/zero forms.
- `replyTokenBindsOriginThreadAndNativeMessageId`: `replyBinding` accepts SMS/MMS URI with positive row and rejects wrong scheme/origin/zero.
- `respondViaMessageAcceptsOnlySupportedSchemeAndSingleRecipient`: `respondRecipient` accepts `smsto` single number and rejects unsupported/multiple/empty or prefixed host-like forms.

`inkpilot/runtime/ToolContactDataTest.kt` (3):

- `recoveredNumberFallbackNeverBecomesModelLabel`: `toolContactText` substitutes Customer for numeric contact display and excludes phone digits.
- `formattedCanonicalAliasesRemainGeneric`: punctuation, nonbreaking spaces, country prefix and blank aliases all remain Customer.
- `actualNamesAndStaffNotesSurvive`: human names and staff-approved note remain readable in model context.

`messaging/ComposerSimSelectionUnitTest.kt` (4):

- `restoreBeforeSetupOverridesPreferenceAndDisplay`: restored subscription 72 wins when cards arrive later and displays slot 2.
- `restoreAfterSetupOverridesPreferenceAndDisplay`: same result when restore follows setup, including subsequent update.
- `singleSimShowsItsExactSubscriptionAndDoesNotSwitch`: selecting next on a single card retains subscription 72 while slot label changes to 1.
- `missingRestoredSimIsVisibleAsUnavailableUntilUserSelects`: missing ID remains stored with `?` label, explicit selection switches to active ID 31.

`messaging/DispatchOutcomeUnitTest.kt` (4):

- `permissionDenialDoesNotReachHandoff`: `MessageDispatcher` rejects and synthetic handoff count stays zero.
- `exactSimAndTextReachHandoffOnceAndThrowIsUnknown`: exact request reaches callback once; exception maps to Unknown.
- `queueAcknowledgmentIsAcceptedWithoutDeliveryClaim`: callback Accepted remains Accepted; this does not assert carrier delivery.
- `missingSubscriptionIsRejectedBeforeHandoff`: ID `-1` cannot reach callback.

`messaging/ExactSubscriptionUnitTest.kt` (1):

- `explicitManagerFailureNeverFallsBackAndDispatchIsUnknownWithoutSending`: injected per-subscription manager failure on two IDs never calls default manager or send; dispatcher result Unknown.

`messaging/MmsImageLimitUnitTest.kt` (1):

- `boundedReadRejectsOversizeAndUnsupportedMime`: `readBoundedMmsImage` rejects 1,048,577 bytes and unsupported MIME, reads three bytes otherwise. It does not decode a JPEG/PNG or prove carrier acceptance.

`receivers/SendStatusCallbackUnitTest.kt` (3):

- `deliveryReportIsParsedBeforeAppCacheIsUpdated`: `finishSendStatusCallback` observes native parse before local status callback.
- `zeroRowOrMismatchedProviderReadbackCannotConfirmCallback`: zero update skips readback; mismatched status cannot confirm local; matching status can.
- `failedNativeUpdateDoesNotRecordAnUnverifiedLocalVerdict`: thrown native update propagates, local verdict callback remains unused.

`receivers/SmsAdmissionTest.kt` (3):

- `failingKeywordOrContactLookupFallsBackToNativeQuarantine`: thrown classification invokes uncertain callback and quarantine native once, not ordinary native/journal.
- `failedPrivateReceiptKeepsFilteredArrivalInNativeProviderOnly`: thrown private journal retains native quarantine and skips ordinary insertion.
- `confirmedPrivateReceiptSuppressesNativeRowAndNormalDraft`: confirmed filtered receipt journals once and does not call either native callback.

`services/HeadlessReplyAdmissionTest.kt` (4):

- `providerLookupFailureStillCommitsHumanTextBeforeAnyDispatch`: `preserve` catches thread lookup exception, persists null thread with saved SIM 7.
- `invalidThreadAndSubscriptionLookupFailureAreStillPersisted`: zero thread and thrown SIM lookup persist null/null and return LookupFailed.
- `validThreadFailedSavedSimLookupCannotDispatchWithOneActiveSim`: valid thread with failed preference lookup persists text but `exactSubscription` remains null even with one active SIM.
- `absentSavedPreferenceStillUsesSingleActiveSim`: genuinely unspecified preference chooses sole active ID, refuses two active IDs.

## Scheduling and calendar

`inkpilot/studio/CalendarTimeRulesTest.kt` (4):

- `springAndFallTransitionsCannotBeBookedAsUnambiguousStaffTime`: `CalendarProposal.validate` rejects DST overlap and spring-gap-crossing interval in Chicago.
- `durationAndMinutePrecisionAreCheckedAtTheStoreBoundary`: subminute and non-minute-aligned proposals reject; 60-second proposal accepts.
- `weeklyHoursFollowWallClockAcrossSpringAndFall`: `CalendarWallHours.fits` follows 09:00–17:00 wall hours on both DST transition days.
- `reminderMustPrecedeItsEntry`: reminder at start rejects, one minute before accepts.

`messaging/ScheduledQueueUnitTest.kt` (4):

- `alarmFailureKeepsPersistedQueuedItem`: `persistThenPost` calls persistence first, catches alarm failure as POST_FAILED and retains recorded order.
- `claimedEditCannotPersistOrPost`: failed claim yields CLAIMED and never calls post.
- `normalQueuePersistsBeforePost`: post observes committed persistence and QUEUED result.
- `invalidSimAndUnreadableMediaRejectBeforeClaim`: `scheduledPreflight` refuses missing SIM and unreadable media without executing forbidden read callback; valid fixture passes.

`messaging/ScheduledVerdictUnitTest.kt` (5):

- `mmsCallbackWithoutVerifiedProviderRowRemainsUnknown`: `applyScheduledCallbackVerdict` routes both accepted/failed callbacks to unknown if no row verified.
- `verifiedMmsCallbackRecordsCarrierResult`: verified success/failure records corresponding boolean, never unknown callback.
- `lateSuccessAfterFailureRemainsUnknown`: `partVerdict` failed and mixed part results stay UNKNOWN.
- `everyPartMustHaveAResult`: one of two parts dispatching, all success confirmed, all failed failed.
- `kindAndAllIdBitsAreInAlarmIdentity`: SMS/MMS namespace and upper ID bits yield distinct alarms.

### Interpretation limits

Most cases inject callbacks into a single helper and assert its return or side
effect, so they protect the local policy seam but cannot establish that a real
provider, Android role, SIM, image decoder, or SMS callback follows that seam.
The scheduled queue and callback device tests cover some of those boundaries;
physical carrier and signed candidate evidence remain separate gates.
