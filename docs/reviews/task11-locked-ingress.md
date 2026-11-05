# Task 11: locked compatibility ingress and auxiliary routes

Base: `94e148d` with Tasks 9, 10, and 14 integrated. Review this change as an isolated Task 11 commit before merging.

## Route ownership

| Entry | Destination in the inkPilot host |
| --- | --- |
| Old explicit Main, thread, new conversation, settings, archive, recycle, details and vCard components | Manifest aliases target a secure, nonvisual compatibility Activity, which forwards a typed route and finishes. |
| Old notification and shortcut thread PendingIntents | Compatibility Activity with a unique thread URI; content PendingIntents are immutable. |
| SMS `SENDTO`/`VIEW`, text or media `SEND`/`SEND_MULTIPLE`, old thread draft | Fresh compose ID, recipients, body, content media, ClipData and read grant on the forwarded intent. Legacy string attachment and JSON group-recipient formats are parsed. |
| `APPLICATION_PREFERENCES`, old Main/Up | In-shell messaging settings and SMS tab. |
| Archive, recycle, details, search, forward | Small panels and long-press message action inside the shell. |

The shell clears its canvas while locked; the shim sets `FLAG_SECURE` before forwarding. Compose state is saved by `ComposerStore` only after unlock. No direct send occurs on ingress.

## Tests and evidence

- `:app:compileFossDebugKotlin :app:compileFossDebugAndroidTestKotlin :app:testFossDebugUnitTest` passed on the Victus build host in `task11-gate.log` before the final forward action was added.
- Review fixes passed `:app:compileFossDebugKotlin :app:compileFossDebugAndroidTestKotlin :app:testFossDebugUnitTest` in `task11-review-gate.log` on the Victus build host. The prior gate is in `task11-final-gate3.log`. The parser retains every recipient/media URI and decodes SMS URI body exactly once regardless of query order.
- `ShellIngressTest` checks manifest aliases, old explicit PendingIntent, preferences, old Main/Up and archive Back, parser formats (including reordered and singly decoded SMS URI query parameters), cold cross-package share ClipData/read grant and durable composer, and locked thread/share with a blank secure canvas. `ShellBaselineTest` checks thread, forward and fresh compose navigation without the inherited Activity. These Android UI tests require a clean disposable hosted emulator with the SMS role; they have not been run in this isolated worktree.
- No phone/SIM mutation or SMS send was performed by Task 11.

## Review risks

- `APPLICATION_PREFERENCES` now opens a compact shell settings panel with the core messaging toggles and Android permission/notification handoffs. Inherited advanced customization and blocking screens remain declared but have no shell navigation; their full feature parity is outside this route slice.
- Search uses the cached conversations database, and archive/recycle are cache-backed. Verify behavior with a populated disposable emulator database before declaring full parity.
- The test APK declares a private, grantable URI provider and starts its own sender Activity in a separate app UID. Its cold share test requires the target to open that URI after the compatibility Activity has finished. Hosted emulator execution is still required before declaring grant behavior proven.
