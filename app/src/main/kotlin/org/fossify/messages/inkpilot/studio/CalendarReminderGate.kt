package org.fossify.messages.inkpilot.studio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A blocked app or channel leaves its saved reminder due for a later retry. */
internal object CalendarReminderGate {
    const val CHANNEL_ID = "inkpilot-calendar"

    fun canPost(context: Context, manager: NotificationManager): Boolean {
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permission) return false
        return runCatching {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Studio reminders", NotificationManager.IMPORTANCE_DEFAULT,
            ))
            allowed(permission, manager.areNotificationsEnabled(),
                manager.getNotificationChannel(CHANNEL_ID)?.importance)
        }.getOrDefault(false)
    }

    fun allowed(permission: Boolean, appEnabled: Boolean, channelImportance: Int?): Boolean =
        permission && appEnabled && channelImportance != null && channelImportance > NotificationManager.IMPORTANCE_NONE
}

internal enum class CalendarReminderAttempt { NO_WORK, RETRY, POSTED }

/** The durable due field is cleared only after posting while notification access remains enabled. */
internal fun attemptDueCalendarReminder(
    store: StudioCalendarStore, id: Long, revision: Long, now: Long,
    canPost: () -> Boolean, post: (CalendarEntry) -> Unit,
): CalendarReminderAttempt {
    val due = store.pendingReminder(id, revision) ?: return CalendarReminderAttempt.NO_WORK
    if (requireNotNull(due.reminderAt) > now || !canPost()) return CalendarReminderAttempt.RETRY
    post(due)
    if (!canPost()) return CalendarReminderAttempt.RETRY
    return if (store.markReminderDelivered(id, revision)) CalendarReminderAttempt.POSTED
    else CalendarReminderAttempt.NO_WORK
}
