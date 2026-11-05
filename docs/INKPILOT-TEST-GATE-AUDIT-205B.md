# InkPilot test-gate audit: pinned e915 tree

This ledger maps test discovery and runner behavior at commit `e915096a` (tree
`205b20f64d0cdf23c557214763a25fd82c0dc1d3`). It is a static selector
inventory and a focused review of the scripts and test cases named below. It
does not claim every assertion in every Android or unit test was read or that
the hosted emulator passed these proposed changes.

## Test inventory and selection

| Source or gate | Pinned e915 count | Selection or implication |
| --- | ---: | --- |
| `app/src/androidTest/**/*Test.kt` | 74 files, 76 classes, 296 `@Test` annotations | Two restart classes share `ComposerPreviewTest.kt` with a third class. Counts are source annotations, not observed Android results. |
| `scripts/inkpilot-shell-baseline.sh` | 48 exact classes | Runs in a disposable Android 35 emulator after first-launch fixture and SMS role setup. Some are repeated by the separate package job. |
| `scripts/inkpilot-device-run.sh` package selectors | 17 package-only classes | Four package selectors (`data`, `studio`, `runtime`, `ui`) also rerun many shell-listed classes on a different emulator. `LegacyFirstLaunchTest` is explicitly excluded from the `data` selector. |
| `scripts/inkpilot-device-run.sh` exact selectors | 6 classes | Local loop, navigation, worker wakeup, native send, batch failure, reviewed-scope smoke; some classes select individual methods in a prescribed process sequence. |
| No CI selector at e915 | 5 classes, 11 methods | `DispatchOutcomeTest` (5), `ToolProtocolDeviceTest` (1), and legacy `ComposerPreviewTest` plus two phase classes (5); the first two are now covered by local commit `231b9931`. |
| `app/src/test/**/*Test.kt` | 15 files/classes, 52 `@Test` annotations | Pinned `inkpilot-build.yml` runs module unit tests but omits `:app:testFossDebugUnitTest`; release and signed beta workflows include it. |

The mutually exclusive Android partition at e915 is **48 shell exact + 17
package-only + 6 device exact + 5 unselected = 76 classes**. The five
unselected classes are `DispatchOutcomeTest`, `ToolProtocolDeviceTest`,
`ComposerPreviewTest`, `ComposerRestartPrepareTest`, and
`ComposerRestartVerifyTest`. `DispatchOutcomeTest` has 5 methods,
`ToolProtocolDeviceTest` has 1, and the three preview classes have 3 + 1 + 1;
thus **11 unselected `@Test` annotations** at e915. (The more recent local
shell gate selects the first two, leaving only the three legacy classes.)

`inkpilot-device-result.py` requires an `OK (N tests)` summary with `N > 0`
and rejects failure markers and instrumentation status codes `-1` through
`-4`, including ignored or failed assumption results. The exact-class runner
records a redacted class/method/file-line verdict and removes its temporary
raw instrumentation output; its emulator check rejects physical devices.
The separate model loop collects raw logcat and screenshots only after its
own QEMU check, on a disposable emulator with synthetic fixture numbers.
This distinction matters: its artifact collector is unsuitable for running
against a real customer's device.

## Findings and changes

1. **Unselected model-free coverage.** `DispatchOutcomeTest` exercises
   rejected media, denied permission, transport uncertainty and durable
   composer handling; `ToolProtocolDeviceTest` checks cross-contact tool
   refusal. Commit `231b9931` adds both exact classes to the shell gate.
   Local fake-adb runner contract tests pass (12/12); a hosted Android verdict
   remains required.
2. **Stale, unselected preview phases.** `ComposerPreviewTest.kt` launches
   `activities.MainActivity` and renders `AttachmentsAdapter`, while current
   `StudioHomeActivity` renders `ThreadComposer` with an attachment label.
   The other two classes in that file require `task5RestartPhase=prepare` and
   `verify`; neither selector nor force-stop sequence is in CI. Running them
   without phase args yields assumption failures, correctly rejected by the
   result parser. The proposed replacement is
   `ShellComposerMediaPrepareTest` -> explicit app force-stop ->
   `ShellComposerMediaVerifyTest`, using the private revocable test provider,
   exact saved recipient/body/URI and a current-shell UI send attempt. The
   revoked URI must be rejected before any carrier handoff; the prepare and
   verify process IDs must differ. The obsolete preview file is deleted in
   the isolated replacement commit. Hosted compilation and emulator execution
   remain outstanding because this environment cannot download Gradle.
3. **Readable but invalid image bytes.** `InShellMmsImage.kt` checks MIME,
   readability and a 1 MiB bound; it does not decode bytes. A provider claiming
   `image/png` with readable corrupt content can pass preflight. This is a
   separate product validation issue; the new revoked-URI test does not prove
   it fixed.
4. **Order and skip behavior.** The first-launch fixture runs before other
   shell classes, and the exact runner preserves independent verdicts after
   assertion failures but aborts after environment failure. UI tests guarded
   with `Assume` on emulator/role/unlocked state cannot silently qualify the
   suite: ignored and assumption failure status codes are rejected. The
   two-phase replacement uses a fixed private composer ID, clears any prior
   fixture before preparation, and cleans it after verification.
5. **Beta evidence and unit gate.** `docs/RELEASE-EVIDENCE.json` has all ten
   physical gates pending. `release.yml` validates successful manual candidate
   provenance, exact artifact and signing hash; `verify_inkpilot_release.py`
   checks the ten gate-specific files and matching APK digest. Integrated
   branch `a52ff792` adds the missing app unit task to the standard build.

## Integrated snapshot, separate from e915

At local `inkpilot-integrate-v3` commit `a52ff792` (tree
`8d9180b593f5d1dc963ee8ff9138158a303b62b9`), static inventory is 87
Android test files, 90 classes, 318 `@Test` annotations; app unit tests are
18 classes and 61 annotations. The exclusive Android selector partition is
63 exact shell classes (already including `231b9931`), 18 package-only,
3 exact device-loop classes, and 6 unselected. The unselected classes are
the three legacy preview classes plus `NativeSendSmokeTest`,
`BatchFailureSmokeTest`, and `ReviewedScopeRuntimeSmokeTest` (4 methods).
The latter three stay unselected because the integrated model loop now
correctly reports `BLOCKED_UNQUALIFIED` and does not invoke or authorize
an unqualified RAW QAD model. The isolated two-process replacement would
retire the three preview classes and add two selected shell classes; this
snapshot must be recalculated after cherry-pick because integration is active.

## Verification and scope

Read end to end: the five workflows, `inkpilot-shell-baseline.sh`,
`run-inkpilot-test-class.sh`, `inkpilot-device-result.py`,
`inkpilot-device-run.sh`, `ComposerPreviewTest.kt`, `DispatchOutcomeTest.kt`,
`ToolProtocolDeviceTest.kt`, `ComposerSharedMediaTest.kt`,
`ComposerShareFailureIngressTest.kt`, `RevocablePreviewProvider.kt`,
`ThreadComposer.kt`, `StudioHomeActivity.kt`, `InShellMmsImage.kt`,
`ComposerStore.kt`, and the relevant test manifest. Other test files were
enumerated by name and `@Test` annotations and selectively inspected; this
is not a line-by-line assertion audit of all 296 tests.

Local checks on the isolated change: `scripts/check-file-size-ratchet.sh`,
`bash -n scripts/inkpilot-shell-baseline.sh`, 13/13 fake-adb runner contract
tests, 30/30 top-level script tests, 17/17 release script tests, and
`git diff --check`. Android compilation did not run locally: Gradle 9.8.1
download failed with `Network is unreachable`. Hosted compilation and device
results must be examined before treating the new phases as proof.
