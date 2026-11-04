# Task 9: in-shell inbox and read-only thread

Base: `1e0efac`; isolated branch `task9-shell-threads`.

## Behavior

The SMS inbox and thread now use RecyclerView inside StudioHomeActivity. Opening an inbox row pushes StudioRoute.Thread; returning pops that tab's stack. Footer and menu remain mounted. New-message stays in the shell with a read-only placeholder until Task 10.

ConversationGateway owns provider paging, SMS/MMS hydration, attachment reads and mark-read. Thread cursors use raw provider milliseconds and thread ID. Message cursors use normalized milliseconds, MessageOrigin and the full provider ID, so equal timestamps, same numeric SMS/MMS IDs and subsecond SMS dates cannot skip rows. Paging has no total conversation cap or offset. Existing blocked/archive filtering remains in conversation hydration.

Thread bubbles show received/outgoing alignment, SMS/MMS type, timestamp, state, MMS image previews and attachment actions. Latest messages start at the bottom; older messages page upward. Provider observers invalidate both the inbox and active thread. Mark-read checks unread/seen rows first to prevent a notification-induced refresh loop on zero-row updates.

StudioPagedList defers RecyclerView restoration until all previously loaded pages exist; pending restoration survives another rotation while pages are loading. StudioCanvasState and StudioNavigator retain tab/thread back stacks and hierarchy state.

## Task 10 integration

- `StudioHomeActivity.openRoute(route)` opens a typed route without leaving the shell.
- `MessagePanel(..., open: (StudioRoute) -> Unit, threadFooter: ((Long) -> View)? = null)` accepts a thread composer footer.
- `MessagePanel.thread(threadId)` owns only read-only message content and the supplied footer; it performs no send.
- Replace the Compose placeholder and provide ThreadComposer via threadFooter; retain the paged list and provider observer.

## Validation

- Main Kotlin compilation: passed.
- Android instrumentation Kotlin compilation: passed with the parent branch's ShellBaselineTest typed-MessageKey cleanup copied temporarily into this worktree. That unrelated cleanup is excluded from this commit.
- `ConversationPagingTest`: 3 host tests passed, 0 failures (full timestamp, cross-origin ties, long IDs, parameter arity and initial boundary).
- `git diff --check`: passed.
- File-size ratchet: Task 9 files have explicit baseline entries; inherited failures remain in LegacyFirstLaunchTest (162 to 179) and Context.kt (1379 to 1384, legacy-over-cap). The original baseline contains a duplicate LegacyFirstLaunchTest key; this change preserves it rather than changing another task's baseline.

`InShellThreadTest` compiles but was not executed: the disposable Victus AVD crashes before boot, and the physical OnePlus must not be substituted. The emulator-only provider test seeds and removes its own 151 synthetic SMS threads plus MMS, verifies all pages, mixed typed identities, footer/tab/back/rotation continuity, provider refresh, mark-read and zero legacy ThreadActivity launches. A second instrumentation test covers pending restoration across repeated rotation. CI must supply the SMS role and READ_SMS/READ_CONTACTS permissions on a disposable emulator.

No live-phone provider writes, outbound messages, pushes or main-branch merges were performed.
