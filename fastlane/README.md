# inkPilot Fastlane

The only lane is `android verify`. It checks source package, privacy copy and
release metadata. It does not build, sign, upload or publish an app.

The manual internal beta workflow downloads the exact debug-signed APK from a
successful manual inkPilot build run. It checks that APK against the physical
evidence and independently builds an unsigned, minified source-quality APK.
The latter is never used as physical candidate evidence. There is no store
deployment lane or public release qualification. See `docs/RELEASE-PRIVACY.md`.
