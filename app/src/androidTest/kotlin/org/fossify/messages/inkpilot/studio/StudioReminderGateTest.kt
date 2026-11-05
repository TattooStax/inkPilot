package org.fossify.messages.inkpilot.studio

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioReminderGateTest {
    @Test fun deniedOrDisabledNotificationsCannotBeCountedAsPosted() {
        val normal = NotificationManager.IMPORTANCE_DEFAULT
        assertFalse(CalendarReminderGate.allowed(false, true, normal))
        assertFalse(CalendarReminderGate.allowed(true, false, normal))
        assertFalse(CalendarReminderGate.allowed(true, true, NotificationManager.IMPORTANCE_NONE))
        assertFalse(CalendarReminderGate.allowed(true, true, null))
        assertTrue(CalendarReminderGate.allowed(true, true, normal))
    }

    @Test fun blockedReminderPersistsAcrossRestartAndPostsOnceAfterReenable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reminder-gate-${UUID.randomUUID()}.db"
        var repository = StudioRepository(context, name)
        try {
            val store = StudioCalendarStore(repository, reminders = { _, _, _ -> })
            store.setStudioZone("UTC")
            val staff = store.addStaff("Maya")
            val start = ((System.currentTimeMillis() / 60_000) + 120) * 60_000
            val reminderAt = start - 60_000
            val saved = store.schedule(CalendarProposal("Consultation", "appointment", staff,
                start, start + 60_000, "UTC", "", reminderAt = reminderAt)) as ScheduleResult.Saved
            var posted = 0
            assertEquals(CalendarReminderAttempt.RETRY, attemptDueCalendarReminder(store, saved.id, 0,
                reminderAt + 1_000, { false }, { posted++ }))
            assertNotNull(store.pendingReminder(saved.id, 0))
            assertEquals(0, posted)
            repository.close()
            repository = StudioRepository(context, name)
            val recovered = StudioCalendarStore(repository, reminders = { _, _, _ -> })
            assertNotNull(recovered.pendingReminder(saved.id, 0))
            assertEquals(CalendarReminderAttempt.POSTED, attemptDueCalendarReminder(recovered, saved.id, 0,
                reminderAt + 1_000, { true }, { posted++ }))
            assertEquals(1, posted)
            assertNull(recovered.pendingReminder(saved.id, 0))
            assertEquals(CalendarReminderAttempt.NO_WORK, attemptDueCalendarReminder(recovered, saved.id, 0,
                reminderAt + 1_000, { true }, { posted++ }))
            assertEquals(1, posted)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun permissionRevokedAfterPostingKeepsReminderForRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reminder-race-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        try {
            val store = StudioCalendarStore(repository, reminders = { _, _, _ -> })
            store.setStudioZone("UTC")
            val staff = store.addStaff("Maya")
            val start = ((System.currentTimeMillis() / 60_000) + 120) * 60_000
            val reminderAt = start - 60_000
            val saved = store.schedule(CalendarProposal("Consultation", "appointment", staff,
                start, start + 60_000, "UTC", "", reminderAt = reminderAt)) as ScheduleResult.Saved
            var checks = 0
            assertEquals(CalendarReminderAttempt.RETRY, attemptDueCalendarReminder(store, saved.id, 0,
                reminderAt + 1_000, { ++checks == 1 }, { }))
            assertNotNull(store.pendingReminder(saved.id, 0))
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }
}
