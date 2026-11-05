# Phone voice path decision — 2026-11-05

## Decision

The sideloaded OnePlus 6T build can be qualified as a **manual default Phone
app**. It cannot yet be qualified as an agent answering cellular calls. A
default-dialer role supplies the call-control UI and lets the app answer a
ringing call; it does not supply the caller's cellular audio samples or a
supported PCM feed into the cellular uplink. Android documents cellular
uplink/downlink capture for privileged preinstalled apps with
`CAPTURE_AUDIO_OUTPUT`, a permission unavailable to ordinary third-party apps.
The absence of a public PCM injection API is an inference from the published
Telecom and media interfaces, not a claim about every vendor or rooted build.

Do not connect the local text model to `Call.answer()` and label the result AI
answering. Such a build would answer silently, leaving a caller in a live call
without an agent conversation.

## Source map

| Boundary | Current source | Consequence |
| --- | --- | --- |
| Role and declaration | `app/src/main/AndroidManifest.xml:57-80`, `phone/PhoneRoleSetup.kt:10-25` | Requests `ROLE_DIALER`, declares `ACTION_DIAL` and an exported `InCallService` with UI metadata. It saves the previous package for manual rollback. |
| Call ingress | `phone/StudioInCallService.kt:12-56,78-109` | Tracks Telecom `Call` objects, creates a generic notification and opens a manual call activity. There is no agent callback, transcript or audio media object. |
| Human controls | `phone/ManualCallController.kt:19-56`, `phone/ManualCallScreen.kt:16-78` | Can answer, decline, hold, route, mute and end. No speech input/output is attached to these controls. |
| Phone tab | `phone/PhonePanel.kt:15-49`, `phone/ManualDialActions.kt:22-43` | Presents manual dialing and an explicit “No AI answering or call audio capture” status. |
| Model route | `app/src/main/kotlin/org/fossify/messages/inkpilot/runtime/RequestDisposition.kt:26-48`, `local-inference/src/main/cpp/inference_generation.cpp:75-79` | Bounded text drafting path; native tool calls are rejected. The model gate is unqualified and off. |
| Permissions | `app/src/main/AndroidManifest.xml:7-30` | No `RECORD_AUDIO` or privileged capture permission. Adding `RECORD_AUDIO` would not grant cellular downlink access. |

## Android API evidence

- [Default Phone app](https://developer.android.com/develop/connectivity/telecom/dialer-app): the role requires an `ACTION_DIAL` activity and a complete incoming/ongoing `InCallService` UI. Emergency calls remain with the preloaded dialer.
- [InCallService](https://developer.android.com/reference/android/telecom/InCallService) and [Call](https://developer.android.com/reference/android/telecom/Call): expose call state, answer/decline and route/mute controls. They do not describe a raw cellular audio media stream for the dialer.
- [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input): during voice calls, an ordinary app cannot capture the call's RX/TX; call capture requires a privileged preinstalled app with `CAPTURE_AUDIO_OUTPUT`. Accessibility capture of microphone input in a call is a different case and does not supply the far-end voice stream.
- [MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource): `VOICE_CALL` and `VOICE_DOWNLINK` require `CAPTURE_AUDIO_OUTPUT`, reserved to system components and unavailable to third-party apps.
- [AudioPlaybackCaptureConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration): projection captures only media, game or unknown usage with opt-in policy, not normal voice communication audio.
- [Telecom overview](https://developer.android.com/develop/connectivity/telecom) and [ConnectionService](https://developer.android.com/reference/android/telecom/ConnectionService): an app-owned VoIP connection can integrate into Android call management while the app itself owns the network media. It is a distinct line from the handset's SIM number.

## Build path and evidence gates

1. Qualify manual cellular Phone on the intended device: role switch and
   rollback, actual incoming/outgoing call, locked-screen notification,
   answer/decline, permission denial, audio routes, dual SIM if present,
   emergency handoff and call history. Record role holder before/after.
2. For an automated voice milestone, choose an **app-owned call medium**:
   local SIP/VoIP media with a studio number or an externally verified
   telephony gateway. Keep the existing Phone canvas, contacts and calendar
   as the UI and record authority. This changes the inbound number path and
   needs separate routing/availability testing. No cloud AI is required for
   local ASR, dialogue and TTS, but a PSTN number may need a provider or a
   physical gateway.
3. First prove an end-to-end media fixture: far-end audio into the app,
   synthesized audio audible to the far end, barge-in/interrupt, silence,
   hangup, staff takeover, and offline failure. Only then attach local speech
   models and an explicitly reviewed, bounded tool policy. Measure latency,
   memory, thermals, call privacy and 20 consecutive phone turns on OnePlus.

Speakerphone-to-microphone acoustic coupling, an accessibility shortcut or
MediaProjection is not the accepted cellular audio path. The app should keep
manual Phone working while this separate voice route is investigated.
