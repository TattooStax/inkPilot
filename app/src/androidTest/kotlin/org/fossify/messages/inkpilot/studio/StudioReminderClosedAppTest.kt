package org.fossify.messages.inkpilot.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.File
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

private const val FIXTURE = "studio_reminder_closed_app"

@RunWith(AndroidJUnit4::class)
class StudioReminderPrecommitTest {
    @Test fun recoveryMustPersistBeforeAnyReminderRowCanCommit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "reminder-precommit-${UUID.randomUUID()}.db"
        try {
            StudioRepository(context, databaseName).use { repository ->
                val normal = StudioCalendarStore(repository)
                val staff = normal.addStaff("Synthetic artist")
                val start = ((System.currentTimeMillis() / 60_000) + 240) * 60_000
                val proposal = CalendarProposal("Synthetic consultation", "appointment", staff,
                    start, start + TimeUnit.HOURS.toMillis(1), normal.studioZone().id, "",
                    reminderAt = start - TimeUnit.HOURS.toMillis(1))
                val interrupted = StudioCalendarStore(repository,
                    reminders = { _, _, _ -> fail("No enqueue is possible before commit") },
                    armRecovery = { error("Recovery registration failed") })
                assertThrows(IllegalStateException::class.java) { interrupted.schedule(proposal) }
                assertTrue(interrupted.entries(start, proposal.endsAt).isEmpty())
                val original = normal.schedule(proposal.copy(reminderAt = null)) as ScheduleResult.Saved
                assertThrows(IllegalStateException::class.java) { interrupted.edit(original.id, proposal) }
                val unchanged = normal.entries(start, proposal.endsAt).single()
                assertEquals(0, unchanged.revision)
                assertNull(unchanged.reminderAt)
            }
        } finally { context.deleteDatabase(databaseName) }
    }
}

/** Preparation and verification run in separate instrumentation invocations, with `am kill` between. */
@RunWith(AndroidJUnit4::class)
class StudioReminderClosedAppPrepareTest {
    @Test fun commitReminderAfterPersistentRecoveryButBeforeOneTimeEnqueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stage = File(context.filesDir, "calendar-closed-app-stage")
        fun record(value: String) { stage.writeText(value) }
        record("entered")
        val preferences = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE)
        assertFalse("Previous fixture must be verified and cleaned first", preferences.contains("id"))
        record("fixture-empty")
        val work = WorkManager.getInstance(context)
        // The earlier shell suite may have armed a sweep that is about to run.
        // Reset its clock before creating the deliberately unscheduled row.
        work.cancelUniqueWork(CalendarReminderRecovery.PERIODIC_NAME).result.get(15, TimeUnit.SECONDS)
        work.cancelUniqueWork(CalendarReminderRecovery.RESTART_NAME).result.get(15, TimeUnit.SECONDS)
        record("cancelled-old-work")
        StudioRepository(context).use { repository ->
            record("repository-open")
            val staff = StudioCalendarStore(repository).addStaff("Synthetic recovery ${UUID.randomUUID()}")
            record("staff-added")
            val start = ((System.currentTimeMillis() / 60_000) + 240) * 60_000
            val proposal = CalendarProposal("Synthetic reminder", "appointment", staff,
                start, start + TimeUnit.HOURS.toMillis(1), StudioCalendarStore(repository).studioZone().id,
                "", reminderAt = start - TimeUnit.HOURS.toMillis(2))
            val interrupted = StudioCalendarStore(repository,
                reminders = { _, _, _ -> error("Simulated process death after commit") })
            assertThrows(IllegalStateException::class.java) { interrupted.schedule(proposal) }
            record("simulated-death")
            val committed = interrupted.entries(start, proposal.endsAt).single { it.staffId == staff }
            assertEquals(proposal.reminderAt, committed.reminderAt)
            record("row-committed")
            val periodic = work.getWorkInfosForUniqueWork(CalendarReminderRecovery.PERIODIC_NAME)
                .get(15, TimeUnit.SECONDS)
            assertEquals(1, periodic.size)
            assertFalse(periodic.single().state.isFinished)
            record("periodic-armed")
            // The preceding synthetic database may have used this numeric id and
            // left a CANCELLED WorkManager row under the same global work name.
            assertTrue(work.getWorkInfosForUniqueWork(
                CalendarReminderScheduler.name(committed.id, committed.revision))
                .get(15, TimeUnit.SECONDS).none { !it.state.isFinished })
            record("one-time-absent")
            assertTrue(preferences.edit().putLong("id", committed.id).putLong("staff", staff).commit())
            record("fixture-persisted")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class StudioReminderClosedAppVerifyTest {
    @Test fun backgroundWakeupRequeuesOnceWithoutOpeningStudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE)
        val id = preferences.getLong("id", -1)
        val staff = preferences.getLong("staff", -1)
        assertTrue("Run the preparation phase first", id > 0 && staff > 0)
        val name = CalendarReminderScheduler.name(id, 0)
        val work = WorkManager.getInstance(context)
        StudioRepository(context).use { repository ->
            try {
                // Do not call reconcile here. An OS-dispatched worker must already have done it.
                val queued = work.getWorkInfosForUniqueWork(name).get(15, TimeUnit.SECONDS)
                    .filter { !it.state.isFinished }
                assertEquals(1, queued.size)
                assertEquals(WorkInfo.State.ENQUEUED, queued.single().state)
                val entry = StudioCalendarStore(repository).pendingReminder(id, 0)
                assertNotNull(entry)
                assertEquals(staff, entry!!.staffId)

                // A later boot/update recovery and the periodic sweep must retain the same work.
                val recovery = CalendarReminderRecovery(context)
                recovery.requestNow()
                recovery.requestNow()
                assertEquals(1, work.getWorkInfosForUniqueWork(name).get(15, TimeUnit.SECONDS)
                    .count { !it.state.isFinished })
            } finally {
                repository.database.delete("studio_calendar", "id=?", arrayOf(id.toString()))
                repository.database.delete("studio_staff", "id=?", arrayOf(staff.toString()))
                work.cancelUniqueWork(name).result.get(15, TimeUnit.SECONDS)
                preferences.edit().clear().commit()
            }
        }
    }
}
