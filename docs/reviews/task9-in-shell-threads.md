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
- Android instrumentation Kotlin compilation: passed against the committed typed-fixture dependency noted below; no temporary fixture edits remain.
- `ConversationPagingTest` and `MessagePresentationTest`: 5 host tests passed, 0 failures (cursor boundaries, typed identity and draft/delivery presentation).
- `git diff --check`: passed.
- File-size ratchet: passed after the committed parent typed-fixture dependency repaired its inherited Context.kt growth and duplicate LegacyFirstLaunchTest baseline key. Task9 owns only its new and changed file entries.

`InShellThreadTest` compiles but was not executed: the disposable Victus AVD crashes before boot, and the physical OnePlus must not be substituted. The emulator-only provider test seeds and removes its own 151 synthetic SMS threads plus MMS, verifies all pages, mixed typed identities, footer/tab/back/rotation continuity, provider refresh, mark-read and zero legacy ThreadActivity launches. A second instrumentation test covers pending restoration across repeated rotation. CI must supply the SMS role and READ_SMS/READ_CONTACTS permissions on a disposable emulator.

No live-phone provider writes, outbound messages, pushes or main-branch merges were performed.


## Independent review repairs

- Hidden thread freshness: provider refresh invalidates every cached Thread route while preserving non-message panels. Returning to SMS recreates the invalidated thread and rechecks read state.
- Retry: StudioWork accepts an error completion; inbox and thread paging reset their loading flag on provider failure, retaining the cursor for an explicit retry.
- Stable anchor: StudioPagedList saves the visible MessageKey (origin plus full provider ID) and decorated edge offset. It pages until that identity exists, restores that identity rather than its previous index, and preserves the pending key through another rotation. Deleted anchors fall back only after source exhaustion.
- Presentation: SMS delivery-report statuses and MMS message boxes have separate mappings. SMS/MMS drafts display Draft. Attachment intent construction is shared with the tested row action.

Regression coverage now includes hidden provider update while Phone is selected, actual inbox RecyclerView pagination and clicking the 151st synthetic thread, injected first-page provider failure then retry for inbox and thread, older-message typed anchor/offset after new insertion and two rotations, MMS image preview/attachment grant metadata, and SMS/MMS draft and delivery status labels. Instrumentation cases remain pending a disposable hosted emulator run; no runtime pass is claimed.

Reproducible compilation uses the parent branch's committed typed-fixture dependency `40d347d`, cherry-picked here as `c9d97ce`, rather than a temporary modified fixture. Main Kotlin and instrumentation Kotlin compilation passed; focused host tests passed 5/5 against this branch's source (`task9-review-fixes-final.log`). When integrating, the parent already has this dependency; cherry-pick only Task9's implementation and repair commits.
