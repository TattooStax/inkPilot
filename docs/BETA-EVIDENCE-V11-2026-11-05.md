# inkPilot v11 local integration checkpoint — 2026-11-05

This is a source-specific status toward the text-first milestone in
[the plan](INKPILOT-PLAN.md). The local integration is `40fe93751`
(tree `f67ddb7d170d341c1b49989df88d53aad323e1e4`). It combines media
repairs with the v10 Studio receipt-policy schema and a separate frozen QAD
holdout report. Isolated branch CI does not certify this combined tree; a
published same-tree verdict is pending. **No functional beta is qualified.**

| Area | Evidence | Open gate |
| --- | --- | --- |
| Shell and media | On isolated `test/share-media-current` (`13041ec9`), build `42001493734`, ratchet `42001493570` and local loop `42001493324` passed. Cross-app share, copy retention, discard recovery and preclaim tests passed before the shell's large-text tab failure. On `test/mms-image-preflight` (`ecf13d95`), build `42008646454`, ratchet `42008646432` and local loop `42008646453` passed. Valid PNG passed; corrupt, truncated and oversized images were refused before claim; shell then failed the separate large-text tab test. On `test/media-restart-java` (`491054fc`), build `42008339284`, ratchet `42008339394` and local loop `42008339289` passed; staged media Prepare/Verify passed across force-stop, then a separate calendar reminder setup failed. | `40fe93751` includes the Java test provider and bounded MIME, 1 MiB, full bitmap decode and null-stream checks (`messaging/InShellMmsImage.kt:13-33`, `MmsImageDecode.kt:9-26`), but its exact-tree hosted shell verdict remains pending. These synthetic checks never transmitted MMS to another handset. |
| OnePlus v8 UI | Preview run `42006533865`: the side-by-side A6013 Android 11 package `app.inkpilot.android.oneplusv8.debug` had APK SHA-256 `711c0427eaae1f725bb8fd49d9e9e2a102cd16441135b88dc861e8a907805cf3`. Five day-to-day canvases, Soul, Messaging settings, Blocked keywords and Back stayed in `StudioHome`; five of five cold Phone ADB taps worked. | This is v8 production source `8daf0cd7`, not v11 `40fe93751`. Generic outline tab icons still miss the requested traditional tattoo motif. Other ingress, lock and large-text routes need one exact-candidate matrix. |
| OnePlus v8 SMS | After temporarily assigning the SMS role, one manual outgoing SMS was submitted through Verizon SIM 1 to the user-authorized personal number. Native provider row ID 63 at 10:18:10 UTC is type 2, status -1, `date_sent=0`. Original SMS role `app.inkpilot.android.debug` was restored. | Coordinate Send taps did nothing; focused keyboard Enter submitted. The row proves a local sent-provider record, **not recipient delivery**. No new inbound, multipart, MMS or unknown/failure carrier result was verified in this pass. |
| OnePlus v10 MMS | Side-by-side preview `app.inkpilot.android.oneplusv10.debug`, run `42011379315` SUCCESS, APK SHA-256 `ecb3067853172eec2009685e84d8ce6b57b81a85d0582e61fbea10df7550758e`, production source `aceea450`, preview source `268e8403`. On the OnePlus 6T, a generated 16×16 PNG decoded in PIL and was selected from Downloads with `ACTION_OPEN_DOCUMENT` after clearing the synthetic MediaStore pending flag. The composer retained the exact authorized recipient, caption and attachment `msf:508`, selected Verizon SIM 1 (`subscriptionId=2`), and received one focused Send action. A new native `content://mms` row `_id=8` has `msg_box=2` (SENT), `m_type=128`, `date=1793876735`, `sub_id=2`, matching target address and three parts including `image/png` and `text/plain`. Reopening the preview showed the caption inside the five-tab `StudioHome` shell. Original SMS role `app.inkpilot.android.debug` and Dialer `com.oneplus.dialer` were restored; watchdog stopped. | This is local provider/callback and in-app history evidence, **not recipient receipt** or a delivery report. No incoming SMS/MMS or AI call answer was tested. ADB picker taps/focus behaved oddly, and a first watchdog script lacked execute permission; the operator manually recovered both test setup issues, with no observed app failure attributed to them. The preview differs from local v11 source. |
| OnePlus v8 Phone | After temporary Dialer-role assignment and `CALL_PHONE`, one short outgoing manual call to the user-authorized number moved `telephony.registry` call state 0 → 2 → 0; `KEYCODE_ENDCALL` ended it. The focus remained on the `StudioHome` Phone canvas and the original `com.oneplus.dialer` role was restored; SMS role remained unchanged. | The in-call canvas exposed no End call or Return to call control. Source checks `activeCalls` only on first render (`phone/PhonePanel.kt:44-48`); service updates do not redraw that canvas (`phone/StudioInCallService.kt:78-98`). Recipient answer/audio, incoming/locked call and full role/route matrix remain open. |
| Local LFM3 | The exact production selector's pinned QAD prompt got 0/24 expected labels with one error; Q4_K_M got 5/13 before stopping. A safer synthetic QAD prompt returned REVIEW for all 24, including all 16 ordinary leads; see [selector evidence](LFM3-EXACT-SELECTOR-CHECKPOINT-2026-11-05.md). A **separate** frozen 36-case bare-field QAD holdout yielded 0/18 useful ordinary replies (12 REVIEW, six errors), 17/18 risky REVIEW (one error, zero risky fields allowed), 17/36 total and seven errors; see its [result receipt](LFM3-TYPED-HOLDOUT-RESULT-2026-11-05.md). | The independent diagnostic fails its preregistered ≥15/18 useful ordinary and zero-error gates. It is not the integrated `LANGUAGE:QUESTION` route and ran no Android role/draft/SMS operation. Production `AgentRuntime.BOUNDED_INTENT_QUALIFIED=false` (`runtime/AgentRuntime.kt:12-13,55-69`); no local model draft or tool action should be enabled from these results. |
| Voice feasibility | [Phone feasibility decision](PHONE-VOICE-FEASIBILITY-2026-11-05.md) maps manual Telecom controls but no supported sideloaded cellular downlink PCM capture/uplink injection. | AI cellular answering is outside this text beta. Investigate an app-owned SIP/VoIP media path or an independently verified gateway for a later voice milestone. A manual call reaching offhook is not an AI conversation. |
| Receipt/data/release | `40fe93751` integrates the v10 durable renderer-policy marker so legacy RAW rows remain readable but cannot pass review or transactional send claim. Isolated `test/bounded-receipt-policy-v10-final` commit `cfa2943a` (tree `c10225d2`) passed build/JVM/Detekt `42011907748` (584 current findings, zero new), packaged SMS-role qualification, ratchet `42011907776`, and `42011907916` local-loop. The emulator data package passed 106 tests including v9 RAW upgrade/reopen, qualified replay/claim and full v1 migration. Its shell passed 65 classes including `StudioDraftPolicyMigrationTest`, `ShellIngressTest` and `OneShellAcceptanceTest`. Studio ZIP export explicitly excludes native SMS/MMS and has no restore importer (`data/StudioArchiveScope.kt:4-8`). | That isolated shell failed only the pre-media-base `ShellComposerMediaPrepareTest` and `ShellComposerMediaVerifyTest` with redacted instrumentation failures. The combined v11 source still needs hosted Android and migration/reopen proof. Stable signing, debug-data migration, provider-plus-Studio restore and physical carrier receipts remain unqualified. |

The frozen holdout was preregistered on isolated `research/voice-path`
(`88ece1d8`), with fixture commit `d34515df2`, independent candidate
`db5cb26aa` and result commit `4e99d58e2`. Corpus
`scripts/data/inkpilot_selector_holdout_36.json` SHA-256
`3a8136519a690144354111face6fbc51df319292fc675277e4bab9425b812f1f`,
candidate instruction SHA-256
`3245f1c343a6a92c24bddaecbb659751458434c881f1965b19be603401cf0204`,
native input SHA-256
`09265aa8f3c8906acd6f8b7e4513b49215dc3ebc6568361cd13102039f063755`,
QAD model SHA-256
`43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452`,
and redacted host report SHA-256
`3aa7a569a6d46991b93493da007d2905c33b8b0e042a1d2de5d02b0db89bf5f5`
pin this result. The host run took 427.39 seconds; successful generations
had p50/p95 9.523/9.837 seconds and peak RSS was 1,148,900 KiB. Raw
synthetic replies remain private. The preregistration and result are separate
from the integrated production selector, and any revised candidate needs a
new frozen comparison.

## Planning judgment

About **45%** remains a subjective implementation-and-qualification estimate
for the agent-assisted *text* beta, using the earlier explicit 100-point rubric:
shell 14/20, Studio 10/15, native SMS/MMS 11/20, useful safe model 4/25,
manual Phone 4/10, and release/restore 2/10. It is not a measured test pass
rate, and zero release gates in `RELEASE-EVIDENCE.json` are accepted. Media
source fixes and the outgoing SMS, MMS and call probes do not change the binding
blockers: the local model is deliberately off, recipient delivery is unconfirmed, the
in-call UI is incomplete, the combined shell is pending and restore is absent.
Future AI voice answering is excluded from this estimate.

## Ordered delta to the plan

1. Publish the combined media/receipt tree; run build, zero-new-debt ratchet,
   full shell, staged force-stop media and local-loop suites on that exact SHA.
   Diagnose large-text tab and asynchronous calendar/notification failures.
2. Fix the in-call Phone canvas refresh or return-to-call route; test real
   incoming/outgoing, locked screen, role rollback, SIM and audio route on the
   intended OnePlus. Keep AI cellular answer absent.
3. Complete controlled physical recipient confirmation for outgoing/incoming
   SMS and image MMS, failure/unknown/retry behavior, process death and native
   thread visibility on a pinned, stable APK with original roles restored.
4. Leave the model gate OFF. Design a new fixed, source-grounded selector,
   blind staff-scored holdout and 20-turn offline OnePlus soak before any
   held model draft can enter review; the tool registry has no production
   model-loop caller.
5. Build and rehearse a truthful provider-plus-Studio restore, stable signer
   and debug-install migration. Finish lead-to-artist appointment/reminder
   workflow and accessibility on the same candidate before beta release.
