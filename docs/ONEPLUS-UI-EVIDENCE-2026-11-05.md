# OnePlus 6T UI route check (2026-11-05)

## Device and build

- Device: OnePlus 6T, Android 11 (API 30), display 1080 × 2340 px at 450 dpi.
- Installed side-by-side preview: `app.inkpilot.android.preview.debug`, source `d0aa0b7` plus preview identity `25dd324`. Installed APK SHA-256: `e45ed78007c56fe74c45f1d0ca4f848593f014d7a3a5685906de8ab2c8445b8d`.
- Existing SMS role holder: `app.inkpilot.android.debug`; dialer role holder: `com.oneplus.dialer`. This check did not change roles or send a carrier SMS/MMS or place a call.
- Original `font_scale=1.0`, automatic rotation enabled (`accelerometer_rotation=1`), `user_rotation=0`. These values and both role holders were restored and verified after the baseline checks.

## Observed routes

| Entry or action | Observation |
| --- | --- |
| Cold Main alias | InkPilot StudioHomeActivity opens on SMS with five bottom tabs; approximately 2.57 seconds to first shell render. |
| Five tabs | SMS, Phone, Contacts, Studio, Calendar stay within the shell after input is accepted. Contacts, albums, calendar controls and phone canvas rendered. |
| Explicit Settings, warm Settings, application preferences | Settings title rendered in the shell; Back returned to SMS. |
| Side card Soul | Assistant title rendered; Back returned to the previous Settings screen. |
| Exported `smsto:` NewConversation alias | New message canvas contained recipient, message, Attach image, SIM choice and Send controls. Attach image opened Android DocumentsUI; Back returned to the same composer. No attachment selected or message sent. |
| Large font | At 1.5× in portrait, all five accessibility tabs remained present and operable, but visual labels touched across boundaries. In landscape at 1.5×, all five labels were readable. |

## Cold input observation

Three fresh Main alias starts showed the first ADB coordinate tap at (324, 2060) on Phone leaving the SMS title unchanged; a second identical tap selected Phone. A fresh 120 ms stationary ADB swipe also missed once. A first ADB menu tap after cold Settings missed; the next tap worked. The Phone accessibility node was enabled/clickable with bounds `[216,2003][432,2125]`, containing (324, 2060). Before and after the tap, `dumpsys input` reported StudioHomeActivity focused, dispatch enabled and unfrozen; `dumpsys power` reported awake/interactive. The bottom screen decoration overlay had no touchable region; navigation bar began at y=2205. This is evidence about ADB injected input only; it does not establish how a human touch behaves or its root cause.

## Pending proof and limits

- The source fix redistributes tab width and presents only the selected held reply on the focused review route. The physical preview build was still active at its last observed heartbeat and Victus then went offline. Its exit verdict, candidate APK identity/hash, installation, and after screenshot remain unverified. No after screenshot is claimed here.
- Archive, Recycle, Details, VCard and historical Thread PendingIntent routes need a physical alias walk. The hosted shell's historical ingress check initially failed at the explicit Settings assertion; a diagnostic found its reused ActivityMonitor returned a destroyed first Home. The fresh-monitor historical alias test passed in the later hosted run.
- Hosted UI-only `4eb53fa` compiled and passed Detekt, local-loop, and 36 of 38 shell classes. The real focused held-review route passed. The remaining shell UI failure was the 1.5× Contacts accessibility click after SMS and Phone had passed. This hosted result does not substitute for a OnePlus screenshot or carrier acceptance.
- Combined hosted `fa4a3e0` passed build/Detekt and local-loop, with 37 of 39 shell classes passing. At the Phone-to-Contacts transition, the active app accessibility root, Contacts nav node, its description and clickable state all passed; the generic description lookup/click still failed. Fresh activity monitors cleared the historical alias test.
- Follow-up hosted source `0311decd` (tree `0491c515`) passed build/Detekt, local-loop and 38 of 39 shell classes. Direct accessibility ACTION_CLICK on the verified Contacts nav node passed; the full 1.5× five-tab and landscape acceptance class passed. This isolates the prior generic description lookup/click as a test helper issue or timing ambiguity. The sole remaining shell class failure is a later cold-share ingress test, under separate investigation.
- Raw UI hierarchies and screenshots are held outside Git at `/home/shawnlowe/inkPilot-physical-test-20261104/ui-audit-20261105/` because device screens may contain private data.
