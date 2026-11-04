package org.fossify.messages.receivers

import org.fossify.messages.messaging.scheduledKey
import org.fossify.messages.messaging.dispatchScheduled
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.THREAD_ID
import kotlin.time.Duration.Companion.minutes

class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakelock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "simple.messenger:scheduled.message.receiver"
        )
        wakelock.acquire(1.minutes.inWholeMilliseconds)

        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                handleIntent(context, intent)
            } finally {
                try {
                    if (wakelock.isHeld) wakelock.release()
                } catch (_: Exception) {
                }

                pendingResult.finish()
            }
        }
    }

    private fun handleIntent(context: Context, intent: Intent) {
        val key = intent.scheduledKey() ?: return // Legacy raw-ID alarms cannot authorize a send.
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val message = context.messagesDB.get(key)?.takeIf { it.threadId == threadId } ?: return
        context.dispatchScheduled(message)
    }
}
