package org.fossify.messages.inkpilot.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioReminderRecoveryTest {
    @Test fun committedDueReminderCanBeRequeuedAfterAnInterruptedEnqueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "reminder-recovery-${UUID.randomUUID()}.db"
        var repository = StudioRepository(context, databaseName)
        var workName: String? = null
        try {
            val staff = StudioCalendarStore(repository, reminders = { _, _, _ -> }).addStaff("Synthetic artist")
            val due = ((System.currentTimeMillis() / 60_000) + 180) * 60_000
            val proposal = CalendarProposal("Synthetic consultation", "appointment", staff,
                due + 30 * 60_000, due + 60 * 60_000, "UTC", "", reminderAt = due)
            val interrupted = StudioCalendarStore(repository, reminders = { _, _, _ -> error("Simulated crash before enqueue") })
            assertThrows(IllegalStateException::class.java) { interrupted.schedule(proposal) }
            val committed = interrupted.entries(proposal.startsAt, proposal.endsAt).single()
            assertEquals(due, committed.reminderAt)
            repository.close()

            repository = StudioRepository(context, databaseName)
            val operations = mutableListOf<Operation>()
            val recovered = StudioCalendarStore(repository, reminders = { id, revision, dueAt ->
                operations += requireNotNull(CalendarReminderScheduler(context).schedule(id, revision, dueAt))
            })
            recovered.reconcileReminders()
            operations.single().result.get(15, TimeUnit.SECONDS)
            val name = CalendarReminderScheduler.name(committed.id, committed.revision)
            workName = name
            recovered.reconcileReminders()
            operations.last().result.get(15, TimeUnit.SECONDS)
            val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(15, TimeUnit.SECONDS)
            assertEquals(1, queued.size)
            assertEquals(WorkInfo.State.ENQUEUED, queued.single().state)
        } finally {
            workName?.let { WorkManager.getInstance(context).cancelUniqueWork(it).result.get(15, TimeUnit.SECONDS) }
            repository.close(); context.deleteDatabase(databaseName)
        }
    }
}
