# Calendar reminder recovery

## Invariant

When a reminder-bearing calendar write begins, a unique periodic WorkManager job is accepted and scheduled **before** the studio database transaction. If that registration fails, the write does not commit. The ordinary versioned one-time reminder is still queued after commit. A process stop in this interval leaves a pending `reminder_at` in the studio database and a previously scheduled recovery job. The recovery worker scans those rows and uses the existing unique `studio-reminder:<id>:<revision>` names to enqueue missing work. It does not copy client names, contact IDs, messages, or appointment titles into WorkManager input or notifications.

The existing revision and `status='scheduled'` checks make stale work harmless after edit or cancellation. A repeated scan retains one pending job per current revision. The assigned artist remains in the canonical calendar row; recovery does not change it. The notification opens the studio route behind the existing app lock and shows only generic text on the lock screen.

At boot after credential storage is available, or when the package is updated, `CalendarReminderRecoveryReceiver` requests an immediate scan. That scan also arms the periodic fallback for an existing installation. WorkManager independently reschedules persisted jobs after reboot.

## Proof procedure

On a disposable emulator with the matching debug app and androidTest APKs installed:

```sh
scripts/inkpilot-calendar-closed-app.sh emulator-5554
```

The script runs a precommit failure check, writes a synthetic reminder while deliberately throwing before its one-time enqueue, exits instrumentation, kills the background app process using `am kill`, and forces **only the already persisted periodic JobScheduler job**. Before another instrumentation process opens, it checks the WorkManager database for exactly one pending one-time job. The verification invocation checks the artist and revision and cleans up the synthetic row. It never launches `StudioHomeActivity` to reconcile. `am force-stop` is intentionally not used because Android blocks jobs and manifest broadcasts for a force-stopped package.

Run the existing `StudioReminderRecoveryTest` and `StudioReminderGateTest` as well for revision, permission, and posting behavior. A pass of this forced-job test does not prove natural periodic timing in Doze, a real reboot on every supported Android version, or notification delivery at an exact instant.

## Beta limits

Periodic WorkManager has a 15-minute minimum interval and can be delayed by battery policy. A recovered reminder may therefore be late; the normal one-time job provides the timely path when its enqueue succeeds. A user force-stop suppresses background work until Android removes the stopped state. Posting a notification and clearing the studio row are separate operations: a crash in between can repeat the alert with the same stable notification ID, so exactly-once alert sound cannot be claimed. These are explicit device acceptance gates, not guarantees supplied by this change.

Android references: [periodic timing](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest), [receiver export and system broadcasts](https://developer.android.com/guide/topics/manifest/receiver-element), [boot storage availability](https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED).
