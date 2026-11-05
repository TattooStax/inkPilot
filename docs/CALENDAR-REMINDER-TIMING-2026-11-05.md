# Calendar reminder timing — 2026-11-05

The local calendar stores `reminder_at` in the Studio database. A normal save
arms persistent recovery before committing the row and enqueues a one-time
WorkManager reminder after commit (`StudioCalendarStore.kt:160-190`). The
15-minute periodic recovery covers a process death in that gap
(`CalendarReminderRecovery.kt:16-27,46-60`). Boot, package replacement, and
opening the calendar also request reconciliation.

The fallback's first run is delayed 15 minutes and later runs can be deferred
by Android. Even the ordinary one-time WorkManager request has an earliest
start, not a punctual delivery guarantee. See Android's
[delayed-work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
and [periodic work contract](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).

If the app stops after a reminder row commits but before its one-time work is
enqueued, a reminder due within 15 minutes may be delivered after the entry
starts. Reconciliation schedules past-due work immediately when it runs, and
the existing gate keeps the due row until a permitted notification posts
(`CalendarReminders.kt:21-27`, `CalendarReminderGate.kt:40-45`). Staff can also
save an entry whose reminder time is already past because proposal validation
checks the reminder's order relative to the entry, not current wall time
(`StudioFeatureRecords.kt:22-32`). The notification now says “calendar entry”
without promising that the entry is still upcoming.

This wording correction does not establish punctual alerts. The closed-app
requeue proof, a near-due crash and late-delivery policy test, notification
permission recovery, and hardware timing under battery restrictions remain
beta acceptance work. If precise reminders become a requirement, design and
verify an Android alarm path and its permission behavior separately.
