package org.fossify.messages.inkpilot.studio

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.ui.StudioHomeActivity

internal class CalendarReminderScheduler(private val context: Context) {
    /** Versioned work names let older edits complete harmlessly after a newer edit. */
    fun schedule(id: Long, revision: Long, due: Long?): Operation? {
        if (due == null) return null
        val request = OneTimeWorkRequestBuilder<CalendarReminderWorker>()
            .setInputData(Data.Builder().putLong("id", id).putLong("revision", revision).build())
            .setInitialDelay(maxOf(0, due - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .addTag("studio-calendar-reminder").build()
        return WorkManager.getInstance(context).enqueueUniqueWork(name(id, revision), ExistingWorkPolicy.KEEP, request)
    }
    companion object { fun name(id: Long, revision: Long) = "studio-reminder:$id:$revision" }
}

internal class CalendarReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        val revision = inputData.getLong("revision", -1)
        if (id < 1 || revision < 0) return Result.failure()
        return try {
            StudioRepository(applicationContext).use { repository ->
                val calendar = StudioCalendarStore(repository)
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                val outcome = attemptDueCalendarReminder(calendar, id, revision, System.currentTimeMillis(),
                    canPost = { CalendarReminderGate.canPost(applicationContext, manager) },
                ) { due ->
                    val intent = Intent(applicationContext, StudioHomeActivity::class.java)
                        .putExtra("inkpilot_panel", "calendar")
                    val pending = PendingIntent.getActivity(applicationContext, 9081, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(applicationContext, CalendarReminderGate.CHANNEL_ID)
                        .setSmallIcon(R.drawable.inkpilot_nav_studio)
                        .setContentTitle("Studio calendar reminder")
                        .setContentText("Open inkPilot to review an upcoming entry.")
                        .setContentIntent(pending).setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                    manager.notify((due.id xor (due.id ushr 32)).toInt(), notification)
                }
                if (outcome == CalendarReminderAttempt.RETRY) Result.retry() else Result.success()
            }
        } catch (_: Exception) {
            // A missing notification permission or storage failure must not erase the reminder.
            Result.retry()
        }
    }
}
