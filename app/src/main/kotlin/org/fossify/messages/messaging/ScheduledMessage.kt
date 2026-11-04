package org.fossify.messages.messaging

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.AlarmManagerCompat
import org.fossify.messages.helpers.SCHEDULED_MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.models.Message
import org.fossify.messages.receivers.ScheduledMessageReceiver

/**
 * All things related to scheduled messages are here.
 */

fun Context.getScheduleSendPendingIntent(message: Message): PendingIntent {
    val intent = scheduledAlarmIntent(message.key(), message.threadId)

    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(this, 0, intent, flags)
}

fun Context.scheduleMessage(message: Message) {
    ScheduledVerdicts(this).use { if (it.state(message.key()) != "QUEUED") return }
    val pendingIntent = getScheduleSendPendingIntent(message)
    val triggerAtMillis = message.millis()

    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
}

fun Context.cancelScheduleSendPendingIntent(key: MessageKey) {
    val intent = Intent(this, ScheduledMessageReceiver::class.java)
    intent.data = Uri.parse(key.alarmIdentity())
    val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    PendingIntent.getBroadcast(this, 0, intent, flags)?.cancel()
}

internal fun Context.scheduledAlarmIntent(key: MessageKey, threadId: Long) =
    Intent(this, ScheduledMessageReceiver::class.java).apply {
        data = Uri.parse(key.alarmIdentity())
        putExtra(THREAD_ID, threadId)
        putExtra(SCHEDULED_MESSAGE_ID, key.id)
    }
