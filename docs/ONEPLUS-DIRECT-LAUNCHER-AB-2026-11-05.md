# OnePlus cold launcher comparison, 2026-11-05

The proposed direct launcher change redirects the 20 existing icon aliases from
`SplashActivity` to `StudioHomeActivity`. It does not change alias component names,
icons, enabled states, historical compatibility aliases, or the SMS role manifest.
This candidate remains isolated on `test/launcher-direct-v6`.

## Controlled physical check

The same OnePlus cold launcher script opened the app and tapped Phone once after
Home focus plus 1.2 seconds on each preview. The observed first-tap results were:

| Preview | First Phone tap | Source |
| --- | ---: | --- |
| v6 inherited Splash launcher | 5/5 | v6 preview of `bfe27892` / local `cacf2ae2` |
| v8 inherited Splash launcher | 5/5 | v8 preview, integration branch |
| v6 direct StudioHome launcher | 5/5 | unique package `app.inkpilot.android.oneplusdirect.debug`, preview `9f1c5d38`, APK SHA-256 `1450524cd1ae2abddd349a4d904c4a4b0df82f6d7999e9f13913a04751649af5` |

The earlier missed tap was observed while `SplashActivity.Green` still held
`mResumedActivity` even though the shell appeared on screen. The controlled
comparison did not reproduce it on either inherited launcher, so these 15
trials do not demonstrate an advantage for the direct target. The unique
preview's SMS and carrier behavior were not part of this comparison; existing
default app roles were left unchanged.

## Startup duties and decision

`BaseSplashActivity.onCreate` checks sideload status and may show the inherited
sideload warning; it then synchronizes Fossify global configuration, theme,
font, and icon color before calling `SplashActivity.initActivity`. The direct
target skips those inherited startup duties. Its component names still match
`checkAppIconColor` and a previously selected icon can still be resolved.
`StudioHomeActivity.onResume` independently applies the app-lock gate and
private canvas protection. The isolated launcher test exercises that guard.

Do not promote the direct alias change on this evidence. Keep the production
launcher path until a controlled reproduction shows a gain and the skipped
inherited duties are intentionally retained or replaced.

## Emulator gate

The isolated source contains a cold launcher smoke script and an instrumentation
test for direct alias target, inherited Splash absence, app-lock gate, empty
locked canvas, and secure window flag. The first hosted run was blocked before
emulator by a missing host fixture copy of the new script. The second run had
an unrelated inherited v6 Android test compile mismatch in calendar test
constructor calls. The source-equivalent test-only correction uses the named
`reminders` argument and will be validated separately. Record hosted results
before using the emulator test as evidence.
