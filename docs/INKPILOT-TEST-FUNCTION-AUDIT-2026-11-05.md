# InkPilot app test function audit

Source pinned to integrated commit `cacf2ae2dd47d3db34bd77462f6b8049ec25f70c`.
No Android test source changed between that commit and the later selector fix
`6264e732`; the latter changes `scripts/inkpilot-device-run.sh`. The method
ledgers below record a complete end-to-end source read of `app/src/androidTest`
and `app/src/test`, not a claim that every method passed hosted Android or
that every production function has a behavioral test.

| Source partition | Kotlin files | Test classes | `@Test` methods | Method ledger |
| --- | ---: | ---: | ---: | --- |
| Android data/runtime | 38 | 34 | 150 | [Data and runtime](INKPILOT-TEST-AUDIT-DATA-RUNTIME.md) |
| Android UI/Studio/phone/staged/transport | 33 | 36 | 97 | [UI and Studio](INKPILOT-TEST-AUDIT-UI-STUDIO.md) |
| Android smoke/messaging | 24 | 23 | 72 | [Smoke and messaging](INKPILOT-TEST-AUDIT-SMOKE-2026-11-05.md) |
| App JVM | 18 | 18 | 61 | [JVM unit](INKPILOT-TEST-AUDIT-UNIT-2026-11-05.md) |
| **Total** | **113** | **111** | **380** | 319 Android + 61 JVM |

The Android 95 Kotlin files comprise **89 `*Test.kt` files and six
helpers/fixtures**. I also read its three Java test fixtures and test-only
manifest. A source-name check finds all **319/319 Android** and **61/61 JVM**
`@Test` method identifiers in the four ledgers. Each method entry names its
production seam, actual assertion, fixture/cleanup and interpretation limit.
Helper methods are described where they affect the fixture or result. The
earlier [pinned e915 gate audit](INKPILOT-TEST-GATE-AUDIT-205B.md) is a
separate source snapshot (74 Android test files, 76 classes, 296 annotations;
15 app JVM files/classes, 52 annotations).

## Exact CI selector map after `6264e732`

| Exclusive assignment | Android classes | Notes |
| --- | ---: | --- |
| Model-free exact shell selector | 66 | Includes first launch, two-phase image media and one-shell UI; some classes are rerun by package suites. |
| Separate calendar closed-app script | 2 | Prepare and verify run around app kill and an OS JobScheduler wakeup; precommit is package-selected. |
| Model-loop package-only | 19 | `data`, `studio`, `runtime`, `ui`, excluding first-launch and four process-phase Studio classes. |
| Model-loop exact classes | 3 | Local-loop methods, home navigation and worker wakeup on a separate disposable emulator. |
| Unselected with unqualified model | 3 | `NativeSendSmokeTest`, `BatchFailureSmokeTest`, `ReviewedScopeRuntimeSmokeTest` (four methods). |
| **Total** | **93** | **315 statically selected methods, four unselected**; runtime verdicts must be checked per job and class. |

The package suites also run many classes already named by the shell suite;
the counts assign each class to its first selector to avoid double-counting.
The four Studio phase classes were unintentionally discovered by the broad
package at `cacf2ae2`, without their process transitions. Fix `6264e732`
excludes those exact classes from that selector while retaining their
dedicated shell/calendar sequences. The result parser rejects empty runs,
failures, ignored tests and assumption failures; it does not treat an
unqualified model as a successful generation.

## Material test defects and fixes

1. **Negative guard could reach a carrier on regression.** In
   `UnqualifiedDraftGuardTest`, the negative `approveAndSend` call used a
   usable SIM and accepted any `IllegalStateException`; if the model gate
   regressed, an emulator send could occur before the assertion. Isolated
   commit `c2c8a2c8` supplies invalid SIM `-1` and checks the exact
   model-gate reason. This is safety-critical test isolation, not a claim
   that the production guard failed.
2. **Historical fixture mirrored current schema.** `StudioV1Fixture` called
   `StudioFeatureSchema.create`, allowing a future production change to
   rewrite the supposed v1 migration input. Isolated commit `234451e8`
   freezes exact historical feature SQL from `5bc2abf`; source comparison
   passed. The Android migration test still needs hosted execution.
3. **Shared WorkManager cleanup could cancel other fixtures.**
   `SmsIntakeContinuationTest` used `cancelAllWork()` on the test-installed
   delegate. Isolated commit `59a6596e` snapshots existing continuation
   work IDs and cancels only IDs created by its method.
4. **Composer assertions could strand synthetic drafts.** Five
   `InShellComposerTest` methods cleared fixed/default-DB composer IDs only
   after assertions succeeded. Isolated commit `fd1a374a` moves cleanup
   into `finally` and resets the fixed preclaim fixture. It needs hosted
   Android compilation and execution.
5. **Historical ingress could leave a live shell after an assertion failure.**
   Isolated commit `9040397e` adds outer cleanup for the alias sequence's
   launched activities and cancels its PendingIntent. This does not change
   routing assertions; hosted device proof remains pending.
6. **First-launch changed later tests' preferences.** Isolated commit
   `35b882cb` snapshots and restores the retired preference, recycle setting,
   run count and on-device role posture token while retaining owned-row cleanup.
7. **Scheduled verdict claims accumulated between methods.** Isolated
   `782457ba` tracks every synthetic identity before mutation and removes
   only its claims/parts in `@After` across 13 methods. It adds one test helper.

All seven isolated fixes above are local review candidates. Source ratchet
and whitespace checks passed; this workspace could not download the Gradle
distribution, so no local Android compile or instrumented result is claimed.

## Remaining limits and order risks

- The pinned `LegacyFirstLaunchTest` leaves its changed retired preference,
  recycle setting, run count and role posture; `35b882cb` is a candidate
  restoration fix. It still requires a hosted Android result.
- `ScheduledVerdictTest` and `ScheduledQueueTest` leave synthetic global
  journal entries at the pinned source; `782457ba` is a candidate exact-key
  cleanup. The pinned `ShellIngressTest` historical alias sequence
  can leave live Activities after an intermediate failure; `9040397e` is a
  candidate cleanup fix. Some Studio image tests
  learn the private copy name only after import succeeds, so a failure can
  leave synthetic files; the two-phase image verify similarly cleans after
  assertions, not in `finally`.
- `StudioRouteTest.stopCancelsQueuedAndLateProviderRefresh` uses a fixed
  400 ms wait for a 300 ms debounce. A stalled looper can make its no-callback
  assertion pass before the callback is due. A clock-controlled scheduler
  would turn this into deterministic cancellation proof.
- Helpers, injected panels and policy truth tables are valuable local
  contracts but do not establish Android role grant, real native send,
  physical MMS delivery, live call answer, or model-family qualification.
  `NativeSendSmokeTest` also has an explicit no-active-SIM branch that can
  pass without sending. Its required prior model outcome is incompatible
  with the current `BLOCKED_UNQUALIFIED` device loop. The actual release
  evidence manifest still marks all ten physical gates pending.

The next verification step is same-SHA hosted Android compilation and
instrumentation for the isolated fixes, followed by physical carrier,
phone and signed candidate evidence under the release gate. A green source
ratchet or method count alone cannot substitute for those results.
