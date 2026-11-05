package org.fossify.messages.inkpilot.studio

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.fossify.messages.inkpilot.data.StudioRepository

/** A persistent wakeup committed before a reminder row, covering death before its one-time enqueue. */
internal class CalendarReminderRecovery(private val context: Context) {
    fun arm() {
        val request = PeriodicWorkRequestBuilder<CalendarReminderRecoveryWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES).build()
        await(WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        ))
    }

    fun requestNow() {
        val request = OneTimeWorkRequestBuilder<CalendarReminderRecoveryWorker>()
            .setInputData(Data.Builder().putBoolean("arm", true).build()).build()
        await(WorkManager.getInstance(context).enqueueUniqueWork(
            RESTART_NAME, ExistingWorkPolicy.KEEP, request,
        ))
    }

    private fun await(operation: Operation) { operation.result.get(8, TimeUnit.SECONDS) }

    companion object {
        const val PERIODIC_NAME = "studio-calendar-recovery-periodic"
        const val RESTART_NAME = "studio-calendar-recovery-restart"
    }
}

internal class CalendarReminderRecoveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (inputData.getBoolean("arm", false)) CalendarReminderRecovery(applicationContext).arm()
        val scheduler = CalendarReminderScheduler(applicationContext)
        val pending = mutableListOf<Operation>()
        StudioRepository(applicationContext).use { repository ->
            StudioCalendarStore(repository, reminders = { id, revision, due ->
                scheduler.schedule(id, revision, due)?.let(pending::add)
            }).reconcileReminders()
        }
        // Success means every requeue was accepted, not just requested asynchronously.
        pending.forEach { it.result.get(8, TimeUnit.SECONDS) }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
