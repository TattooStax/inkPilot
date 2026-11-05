# inkPilot text beta privacy and release claims

This is the source contract for an **internal local text beta candidate**, not
a public release. The existing `inkpilot-build` manual workflow uploads a
debug-signed `inkPilot-foss-debug` APK. Download that exact artifact, install
it on the intended OnePlus, and record its SHA-256, signing certificate digest,
build run ID and source SHA in `RELEASE-EVIDENCE.json`. A reviewer must inspect
redacted proof for every gate before marking it passed. Each gate has its own
`docs/release-evidence/<gate>.md` file with an `APK SHA-256: <digest>` line.

The manual `inkPilot signed canonical beta build` workflow can instead
produce a minified `app.inkpilot.android` APK with a durable studio-controlled
signing key. It runs only by dispatch, using the protected
`inkpilot-beta-signing` GitHub environment. Configure five environment
secrets there: `INKPILOT_BETA_KEYSTORE_BASE64` (base64 of the binary keystore),
`INKPILOT_BETA_KEY_ALIAS`, `INKPILOT_BETA_KEY_PASSWORD`,
`INKPILOT_BETA_STORE_PASSWORD`, and `INKPILOT_BETA_CERT_SHA256` (the expected
lowercase certificate digest). Commit that public 64-character digest as
the sole line of `docs/BETA-SIGNING-CERT.sha256` **before** dispatch; the
builder requires the reviewed file and environment value to match the APK.
The digest file is intentionally absent until a durable key is provisioned,
so signed candidate builds fail closed. Store and back up the original
keystore outside the repository; losing it prevents future in-place upgrades.
The workflow writes it to a private runner temp file for the build, removes
it afterward, verifies the package, certificate and SMS role, then uploads
only the signed APK. Missing or changed secrets fail the build.

The manual internal beta qualification workflow accepts the run ID and
`candidate_kind` (`debug` or `release`), requires a successful same-repository
`workflow_dispatch` run from the matching build workflow on the same
branch as qualification, and downloads its exact artifact. It rejects source
changes after the candidate build except the evidence manifest and gate proof
files. It validates the downloaded APK's signature, certificate, package
and SHA-256 against the physical evidence. The debug candidate remains
available for compatible existing debug installs. A separate unsigned
minified release build checks source quality; its bytes are **never** treated
as the installed candidate. The qualification workflow has no public
publishing lane or signing credentials. `--release` stays red until a
separately qualified public distribution process exists.

Evidence paths and statuses remain human attestations, not cryptographic proof
that carrier testing, accessibility review or privacy inspection occurred.
The reviewer must compare the OnePlus-installed APK's hash with the downloaded
artifact and inspect redacted records for the exact candidate. Hosted debug
builds can use ephemeral signing keys, so a newer artifact may not upgrade an
already-installed debug package. Perform the read-only signer comparison in
`DEVICE-ACCEPTANCE.md` before any phone install or role change. A mismatch
blocks in-place upgrade; do not uninstall customer data to bypass it. Repeatable
internal upgrades need a stable signing key, or a separately proven complete
backup/restore and SMS/Phone role rollback path. Shell Settings can save a
plaintext, user-selected Studio ZIP containing database rows and retained
reference originals with per-entry SHA-256. The manifest is written last and
unreadable originals or an app lock stop the export; a provider may leave an
incomplete document if deletion fails. A content-free cleanup notice persists
until staff acknowledge it after unlocking, with no URI or customer data saved. This is an export-only format: it excludes
native SMS/MMS, app preferences and model weights, and has no tested import.
It does not clear the physical restore gate.

| Data or action | Current boundary | Claim allowed after device proof |
| --- | --- | --- |
| SMS and MMS | Android provider, SIM and carrier; MMS uses carrier mobile data | Staff can see/send in the inkPilot shell with verified receipts |
| Local model | Staff-imported GGUF, app-private files and native on-device inference | Held text draft works offline on the qualified handset/model hash |
| Contacts, memory, Studio, calendar | On-device databases and media originals | Linked records survive restart, migration and a tested backup/restore |
| Companion bridge | Network-paired optional code, off by default; excluded from beta | No default cloud account, server or connection is required for local drafts |
| Phone | Staff-controlled dialer role and call UI | Manual calls work after role/permission acceptance; no AI cellular answering |

The app requests SMS, contacts, phone/SIM and optional media access as needed for
these flows. The model receives selected local text context and cannot directly
send, book or select a recipient. Staff approval and the carrier path are separate.
A network-free local inference result is not proof that SMS/MMS has no carrier
network activity, or that optional bridge functionality never uses a connection.

The old Fossify package ID, store text and reusable release workflow cannot
publish this application. Source namespace and GPL notices remain for code
provenance. Translations of the inherited Internet FAQ were removed until a
speaker can review an accurate locale-specific replacement; Android uses the
English default for that answer. The active store listing is English only. A
human reviewer must check TalkBack, 1.5x text, contrast, landscape and the actual
physical carrier path before marking accessibility/privacy as passed. The
emulator checks five-tab label bounds and contrast, but it does not replace
TalkBack and handheld inspection of held-reply controls.

No customer phone number, message, photo, model prompt or call recording belongs
in public evidence. Use synthetic fixtures and redacted screenshots/logs. AI
cellular call audio is outside this text release. See `DEVICE-ACCEPTANCE.md` for
the required physical test and `RELEASE-EVIDENCE.json` for remaining gates.

## Static analysis debt

The app's Detekt report currently has 731 unsuppressed findings (783 weighted),
mostly `MagicNumber` and `MaxLineLength` in inherited and new UI/data code.
`app/detekt-debt.jsonl` records their exact source fingerprints; the build runs
Detekt and fails on any new or increased finding through
`scripts/check-detekt-debt.py`. Resolved findings may disappear from the report.
This is an interim debt ratchet, not a claim that Detekt has no findings. The
in-shell adapter has a distinct class and filename so inherited suppressions
cannot hide its findings. The high-complexity paths still require refactoring.
