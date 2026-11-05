# inkPilot text beta privacy and release claims

This is the source contract for an **internal local text beta candidate**, not
a public release. The existing `inkpilot-build` manual workflow uploads a
debug-signed `inkPilot-foss-debug` APK. Download that exact artifact, install
it on the intended OnePlus, and record its SHA-256, signing certificate digest,
build run ID and source SHA in `RELEASE-EVIDENCE.json`. A reviewer must inspect
redacted proof for every gate before marking it passed. Each gate has its own
`docs/release-evidence/<gate>.md` file with an `APK SHA-256: <digest>` line.

The manual internal beta qualification workflow accepts that build run ID,
requires a successful same-repository `workflow_dispatch` run, and downloads
its artifact. It rejects source changes after the candidate build except the
evidence manifest and gate proof files. It validates the downloaded APK's
signature, certificate, `app.inkpilot.android.debug` package and SHA-256 against
the physical evidence. A separate unsigned minified release build provides
lint/code-quality evidence; its bytes are **never** treated as the installed
APK. No public publishing lane or signing credentials are configured in this
qualification workflow. `--release` stays red until a separately signed public
candidate and distribution process are implemented and physically tested.

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
incomplete document if deletion fails. This is an export-only format: it excludes
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
