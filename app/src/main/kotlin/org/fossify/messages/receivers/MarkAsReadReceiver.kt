package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.helpers.MARK_AS_READ
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.inkpilot.runtime.AgentSettings

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MARK_AS_READ -> {
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                val pending = goAsync()
                ensureBackgroundThread {
                    try {
                        if (context.markThreadMessagesRead(threadId)) {
                            context.notificationManager.cancel(threadId.hashCode())
                            refreshConversations()
                        } else {
                            runCatching { AgentSettings(context).recordStatus(
                                "Message read status was not confirmed. Open SMS to review it.") }
                        }
                    } finally { pending.finish() }
                }
            }
        }
    }
}
