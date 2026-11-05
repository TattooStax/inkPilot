# Remaining transport, manifest, and build audit — tree `205b20f`

Source pin: commit `e915096acfa7da10c19538f104f2f4c8aed543ca`, tree `205b20f64d0cdf23c557214763a25fd82c0dc1d3`. This ledger audits the executable bodies and declarations in all 19 `:transport` production Kotlin files, all 47 app `transport/` production Kotlin files, and both app `services/` files. It also reads the manifest, build definitions, relevant XML resources, five build/release workflows and twelve qualification and device scripts. Runtime, Studio, UI, and legacy receiver audits live in their separate ledgers. This branch includes a one-line optional-bridge cleanup change and an instrumentation regression test; findings about pinned source are identified separately from that change.

## Paths and callers

| Entry | Call chain and durable result | Boundary |
| --- | --- | --- |
| Android `RESPOND_VIA_MESSAGE` | manifest permission → `HeadlessSmsSendService` → `HeadlessReplyAdmission` → `DirectReplyPendingStore` → native `sendMessageCompat` → held record completion or staff status | Exported service is gated by `SEND_RESPOND_VIA_MESSAGE`; source at e915 checked role before holding; root's integrated work fixes this separately. |
| Legacy SMS composer | `Messaging.sendSms` → `LocalMessageTransport.sendSms` → `messagingUtils.sendSmsMessage` with provider role, or permissions-only direct radio path with result receiver | This route is inherited UI plumbing. The current shell admission and display paths have their own runtime/UI audit. `TransportOutcome.Sent` means the native submit returned, with asynchronous carrier verdict elsewhere. |
| Optional pair | old `SettingsActivity` pairing scanner → `PairingSetup` → QR parser → `PairingExchangeBinding` → `CredentialStorage` → heartbeat, WorkManager drains and pickup | `OPTIONAL_BRIDGE_ENABLED` defaults false. Current historical Settings alias routes into shell, so this old setup UI is not a user path in standalone beta. Pairing exchange also fails closed when flag is false. |
| Optional inbound bridge | legacy SMS receiver calls `holdAndDrainInboundArrival` → committed pairing-scoped queue → `HeldDrainWorker` → `InboundDeliveryBinding` → ack removes head | MMS and missing provider identity are refused by pure held-entry rule. Failed delivery retains head; periodic network-constrained drain retries. |
| Optional outbound bridge | periodic/one-shot `OutboundPickupWorker` → authenticated pickup → `OutboundWorkSender` → provider outbox receipt and pinned active SIM → `OutboundSendResultReceiver` → completed verdict/reconcile receipt → pending ack queue → `PendingReportDrainWorker` | Source claim and persisted progress avoid duplicate pickup after process death. Success is filed only from radio callback, not from submission. |
| Boot and alarm | `HeartbeatBootReceiver`/`HeartbeatAlarmReceiver` → credential gate → foreground beat or async fallback → next alarm and WorkManager schedules | No stored credential means no beat or delivery on the standard standalone build. |

### Pure transport function inventory

Each row is a complete executable function inventory for its file (including private JSON helpers); data-only declarations are noted by their type names. This maps the source function to its caller lane above.

| File | Functions and purpose |
| --- | --- |
| `BridgeReceiptReconciliation.kt` | `bridgeReceiptUpdateSettles`: success on updated provider row, or confirmed absent row after zero updates. |
| `BridgeSubscription.kt` | `capture`, `dispatchIfCurrent`: pin current default active SIM and recheck before radio dispatch. |
| `DefaultSmsRole.kt` | `defaultSmsRoleStep`: choose role prompt or legacy default flow from platform state. |
| `DrainRetry.kt` | `drainRetryIntervalMillis`: exposes heartbeat-length retry cadence. |
| `FailureHonesty.kt` | `bridgeHealthAt`, `inboundAckStateAfter`, `outboundAckStateAfter`, `isReceivedLate`: monotonic ack, overdue/stale and late-arrival definitions. |
| `HeartbeatSchedule.kt` | `nextHeartbeatDelayMillis`, `heartbeatNotificationStateFor`: first-beat/periodic schedule and notification verdict. |
| `HeartbeatWire.kt` | `HeartbeatRequest.toString`, `heartbeatRequestFor`, `base64Url`, `heartbeatOutcomeFor`, `jsonString`: authenticated body/header and 204-only recording. |
| `HeldQueue.kt` | `heldInboundMessageFor`, `heldQueueAfterArrival`, `nextHeldInbound`, `heldQueueAfterDelivery`: valid source id, FIFO dedupe and ack-only release. |
| `Inbound.kt` | `InboundSms.sourceId`, `inboundDispositionFor`, `InboundSink.onInbound`: SMS provider id and MMS/deferred classification. |
| `MessageDeliveryWire.kt` | `InboundDeliveryRequest.toString`, `inboundDeliveryRequestFor`, `base64Url`, `inboundDeliveryOutcomeFor`, `inboundAckStateAfterDelivery`, `jsonString`: source id and late flag sent with redacted auth; 204-only ack. |
| `MessageTransport.kt` | `MessageTransport.sendSms`; `OutboundSms` and `TransportOutcome` carry native submit result. |
| `OutboundAckWire.kt` | `OutboundAckRequest.toString`, `outboundAckRequestFor`, `base64Url`, `outboundAckOutcomeFor`, `outboundAckStateAfterSend`, `jsonString`: final radio verdict by source id; 204-only report ack. |
| `OutboundPickupWire.kt` | `OutboundPickupRequest.toString`, `outboundPickupRequestFor`, `base64Url`, `outboundPickupOutcomeFor`, `outboundWorkFromFields`, `nonNegativeMillis`, `jsonString`, `flatJsonStringObject`, `PickupFlatJsonReader.isAtEnd/readObject/readString/readEscape/readUnicodeEscape/hexDigit/consume/skipWhitespace`: strict flat JSON, duplicate rejection, bounded field validation. |
| `OutboundRouting.kt` | `outboundChannel`: SMS/MMS choice from attachment, length and group policy. |
| `Pairing.kt` | `PairingOffer.toString`, `pairingOfferFromQrPayload`, `percentDecoded`, `hexDigit`, `pairingTokenStateAt`, `pairingStateAfter`, `isPairingCodeFormat`: QR authority/path/token validation, token expiry and setup state. |
| `PairingExchange.kt` | `DeviceCredential.toString`, `isEndpointBase`, `isLoopbackAuthority`, `isOpaqueValue`, `exchangedCredentialFor`, `pairingEventForExchangeFailure`, `deviceCredentialFromStored`, `isCredentialUsableFor`, `pairingStateAfterDisconnect`: HTTPS or exact loopback HTTP, minted credential and no-backup token binding. |
| `PairingExchangeWire.kt` | `PairingExchangeRequest.toString`, `pairingExchangeRequestFor`, `pairingExchangeOutcomeFor`, `jsonString`, `flatJsonStringObject`, `FlatJsonReader.isAtEnd/readObject/readString/readEscape/readUnicodeEscape/hexDigit/consume/skipWhitespace`: one-time token exchange and strict response parser. |
| `PendingReportQueue.kt` | `pendingOutboundReportFor`, `pendingReportQueueAfterVerdict`, `nextPendingOutboundReport`, `pendingReportQueueAfterReport`: deduped FIFO verdicts released on report ack. |
| `Posture.kt` | `rolePostureForDedicatedBusinessPhone`, `rolePostureFromStoredName`, `rolePostureFromStoredAnswer`: legacy personal/default-role setting and no-backup answer binding. |

### Android transport function inventory

| File | Functions and caller relation |
| --- | --- |
| `BridgeReceiptRecovery.kt` | `enqueueBridgeReceiptRecovery`: retained final verdict → unique WorkManager drain. |
| `BridgeSourceReceipt.kt` | `receiptKey`, `receiptPrefs`, `claimBridgeSource`, `createBridgeReceipt`, `reconcileBridgeReceipt`: source identity claim, provider outbox row and terminal sent/failed status. |
| `CompletedOutboundVerdict.kt` | Data-only immutable `CompletedOutboundVerdict`, consumed by retained-filing worker. |
| `CredentialGuard.kt` | Data-only lock protecting credential and pairing-scoped queue snapshots. |
| `CredentialStorage.kt` | `storedDeviceCredential`, `storeDeviceCredential`, `storeDeviceCredentialGuarded`, `hasStoredCredentialMaterial`, `clearDeviceCredential`, `clearDeviceCredentialGuarded`: preference secret plus no-backup device token, synchronized mutation. |
| `DefaultSmsRoleSetup.kt` | `launchDefaultSmsRoleSetup`, `isDefaultSmsApp`: legacy activity role request; shell owns current role UI. |
| `DrainExecutionGuard.kt` | Data-only per-queue locks for worker/arrival overlap. |
| `DrainRetrySchedule.kt` | `ensureDrainRetrySchedule`, `cancelDrainRetrySchedule`: periodic held inbound and pending report workers, network required. |
| `HeartbeatAlarmReceiver.kt` | `onReceive`, `heartbeatAlarmIntent`, `scheduleNextHeartbeat`, `cancelHeartbeatSchedule`: credential check, one beat, next alarm. This branch fixes missing outbound pickup cancellation. |
| `HeartbeatBinding.kt` | `performHeartbeat`: authenticated POST, timeout, no redirect; pure wire parser decides outcome. |
| `HeartbeatBootReceiver.kt` | `onReceive`: boot action and credential gate, restore beat/drain/pickup schedules. |
| `HeartbeatNotification.kt` | `createHeartbeatChannel`, `buildHeartbeatNotification`, `heartbeatStateText`, `postHeartbeatNotification`, `cancelHeartbeatNotification`: persistent state and shell ingress. |
| `HeartbeatService.kt` | `onCreate/onStartCommand/onTimeout/onDestroy/onBind`, `runOneBeat`, `startForegroundWithState`, `runHeartbeatBeatOnce`, `tryStartHeartbeatBeatService`, `startHeartbeatService`, `ensureHeartbeatSchedule`, `stopHeartbeatService`: bounded wake lock, foreground beat, notification and scheduling. |
| `HeldArrival.kt` | `holdAndDrainInboundArrival`, `enqueueHeldInboundDrain`: commit and readback before enqueue. |
| `HeldDrainWorker.kt` | `doWork`: serialize drain; periodic retry covers blocked result. |
| `HeldQueueDrain.kt` | `drainHeldInboundQueue`: snapshot credential/queue, POST head, commit ack-only release under ordered locks. |
| `HeldQueueDrainResult.kt`, `HeldQueueGuard.kt` | Data-only result variants and queue lock. |
| `HeldQueueStorage.kt` | `storedHeldInboundQueue`, `storeHeldInboundQueue`, `storedHeldMessageAt`, `decodedHeldMessageAt`, `storedNullableLongIsReadable`, `storedNullableLong`, `putHeldMessage`, `removeHeldMessage`: pairing-scoped serialized queue and commit/readback. |
| `InboundDeliveryBinding.kt` | Public/internal `performInboundDelivery`: pure disposition, authenticated POST, no redirect, timeout. |
| `LocalInboundSink.kt` | `onInbound`: pure SMS/MMS disposition adapter. |
| `LocalMessageTransport.kt` | `messageTransport`, `sendSms`, `sendSmsWithoutProviderRow`, `sentResultIntent`, `showSmsTransportFailure`: legacy composer path and result callback. |
| `OutboundAckBinding.kt` | Public/internal `performOutboundAckReport`: authenticated verdict POST, no redirect, timeout. |
| `OutboundPickup.kt` | `pickUpAndSendOutboundWork`, `sendPickedUpWork`: credential/switch recheck and repeated pickup until no new work. |
| `OutboundPickupBinding.kt` | Public/internal `performOutboundPickup`, `readPickupResponseBody`: authenticated POST, strict UTF-8 and 16 KB response limit. |
| `OutboundPickupGuard.kt` | Data-only pickup lock. |
| `OutboundPickupSchedule.kt` | `ensureOutboundPickupSchedule`, `enqueueOutboundPickup`, `cancelOutboundPickupSchedule`: periodic and immediate WorkManager pickup. |
| `OutboundPickupWorker.kt` | `doWork`: serialized pickup, success after poll; periodic schedule supplies retry. |
| `OutboundSendProgressGuard.kt` | Data-only callback progress lock. |
| `OutboundSendProgressStorage.kt` | `progressKeyStem`, `progressPrefs`, `beginOutboundSendProgress`, `recordOutboundSendPart`, `progressEntry`, `storeProgressMask`, `completeProgress`, `clearOutboundSendProgress`: per-part durable radio callback fold. |
| `OutboundSendRecoveryStorage.kt` | `storedCompletedOutboundVerdicts`, `completedVerdict`, `storeCompletedOutboundVerdict`: durable completion marker for retry after process death. |
| `OutboundSendResultReceiver.kt` | `onReceive`: result code → part fold → provider reconciliation/report queue. |
| `OutboundVerdictFiling.kt` | `fileOutboundVerdict`, `fileRetainedOutboundVerdicts`: reconcile provider, then pair-matched brain report, retain unsettled. |
| `OutboundWorkSendResult.kt` | Data-only `OutboundWorkSendResult` variants. |
| `OutboundWorkSender.kt` | Public/internal `sendOutboundWork`, `sendGuardedOutboundWork`, `synchronousFailure`, `outboundSentResultIntent`: role check, claim, provider receipt, active SIM recheck and radio callbacks. |
| `PairingAttemptGuard.kt` | Data-only atomic in-flight pairing guard. |
| `PairingDisconnect.kt` | `confirmPairingDisconnect`, `disconnectPairing`: user confirmation, clear credential and schedules. |
| `PairingExchangeBinding.kt` | `performPairingExchange`, `readExchangeResponseBody`: flag gate, bounded strict UTF-8 response and durable credential store. |
| `PairingSetup.kt` | `registerPairingScanner`, `launchPairingScan`, `handleScannedPairingPayload`, `pairingFailureMessage`: legacy scanner and setup lifecycle. |
| `PendingReportDrainWorker.kt` | `doWork`: retained verdict filing, queue drain and retry when retained completion remains. |
| `PendingReportQueueDrain.kt` | `drainPendingOutboundReportQueue`: pair-matched FIFO report with ack-only release. |
| `PendingReportQueueDrainResult.kt`, `PendingReportQueueGuard.kt` | Data-only result variants and queue lock. |
| `PendingReportQueueStorage.kt` | `storedPendingOutboundReportQueue`, `storePendingOutboundReportQueue`, `storedPendingReportAt`, `decodedPendingReportAt`, `storedNullableIntIsReadable`, `storedNullableInt`, `putPendingReport`, `removePendingReport`: pairing-scoped serialized verdict queue. |
| `PendingReportVerdict.kt` | `holdAndDrainOutboundVerdict`, `enqueuePendingOutboundReportDrain`: commit/readback before WorkManager drain. |
| `PermissionsOnlySendResultReceiver.kt` | `onReceive`: asynchronous legacy direct-send refresh and error toast. |
| `PostureSetup.kt` | `storedRolePosture`, `storeRolePosture`, `ensureRolePosture`: legacy answer gate with no-backup token. |
| `services/HeadlessReplyAdmission.kt` | `Admission.exactSubscription`, `preserve`: nullable thread and exact SIM snapshot before hold. |
| `services/HeadlessSmsSendService.kt` | `onBind`, `onStartCommand`: permission-gated external respond-via-message → durable hold → native submit. |

## Configuration and script inventory

| Files read | Contract checked |
| --- | --- |
| `build.gradle.kts`, `settings.gradle.kts`, `transport/build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` | Distinct standalone ID, source namespace, foss debug/release, `:transport` dependency, min/target API, native ABI, dormant optional flag, unit/lint/detekt configuration. |
| `app/src/main/AndroidManifest.xml` | One enabled launcher, guarded historical aliases, SMS role components/permissions, nonexported bridge receivers/services, FileProvider, app permissions and boot entry. |
| `app/src/main/res/xml/network_security_config.xml`, `provider_paths.xml` | Loopback-only cleartext for optional bridge; granted private cache/files share roots. |
| `.github/workflows/inkpilot-build.yml`, `inkpilot-device.yml`, `inkpilot-beta-build.yml`, `release.yml`, `file-size-ratchet.yml` | Push and manual build gates, emulator model-free/model-backed jobs, signed beta workflow, candidate provenance and release evidence. |
| `scripts/check-detekt-debt.py`, `check-file-size-ratchet.py`, `check_candidate_run.py`, `verify_app_identity.py`, `verify_inkpilot_release.py`, `verify_sms_role_qualification.py` | Debt fingerprint, file size, exact candidate SHA and run, identity, public release block, merged/APK SMS role contract. |
| `scripts/inkpilot-device-model.sh`, `inkpilot-device-result.py`, `inkpilot-device-run.sh`, `inkpilot-shell-baseline.sh`, `inkpilot-sms-role-preflight.sh`, `run-inkpilot-test-class.sh` | Pinned model hash, instrumentation nonempty success, disposable emulator role/provider preflight, per-class verdicts and synthetic SMS flow. |

## Findings and disposition

| Priority | Pinned evidence | Action/status |
| --- | --- | --- |
| P1 in e915 | `HeadlessSmsSendService.onStartCommand` returned before durable hold when SMS role was absent. Reply entry could vanish; it also passed text to `sendMessageCompat`, whose long-SMS setting could promote direct reply to MMS. | Root owns the integrated admission and SMS-only correction; verify against its changed tree and device tests. This audit branch does not edit it. |
| P2 in e915 | `.github/workflows/inkpilot-build.yml` built debug APK without `:app:testFossDebugUnitTest`. | Root's integrated build-gate patch adds app unit tests. Recheck final workflow run. |
| P3 optional only | `HeartbeatAlarmReceiver.onReceive` canceled heartbeat/drain work with no credential, but missed `cancelOutboundPickupSchedule`; scheduled periodic pickup would continue waking after credential loss, although its credential gate prevents sending. | Fixed here by one cancellation call; `HeartbeatAlarmCleanupTest` enqueues both unique pickup jobs, enters no-credential alarm path and checks WorkManager cancellation. The standalone build keeps the bridge off. |
| Product boundary | ~7.8k lines of pure+Android transport/services ship compiled for a future optional bridge even though the current shell pairing route is dormant. This adds maintenance and old settings vocabulary, with no observed standalone outbound network start on fresh install. | Keep flag false for beta, treat the bridge as a separately qualified future adapter. Do not present its setup as completed OpenFang integration. |

The pairing and wire readers constrain URLs to HTTPS or exact loopback HTTP, disable redirects, bound pickup/exchange response bodies, and redact credentials in their renderings. FIFO held and report queues use a stable pairing identity, synchronized commit/readback, and retry. The known P3 path is a scheduler cleanup gap, not an observed unsolicited bridge connection on the standard build.

## Verification and limits

Static checks on pinned source passed: `python3 scripts/verify_app_identity.py`, `python3 scripts/verify_inkpilot_release.py --source`; 17 `scripts/tests` unit tests; SMS-role preflight script tests; 12 exact-class runner script tests. `./gradlew :transport:test --offline --no-daemon` could not start: the Gradle 9.8.1 distribution is absent and the sandbox cannot reach `services.gradle.org`. The newly added Android WorkManager test has not run here and requires the disposable emulator job after integration. This ledger records source behavior; it does not substitute for current-tree APK signing, OnePlus carrier/MMS/SIM testing, physical call qualification, or model benchmark evidence.
