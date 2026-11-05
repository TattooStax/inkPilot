package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.deleteMessage
import org.fossify.messages.extensions.updateLastConversationMessage
import org.fossify.messages.helpers.IS_MMS
import org.fossify.messages.helpers.MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.inkpilot.runtime.AgentSettings

class DeleteSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(MESSAGE_ID, 0L)
        val isMms = intent.getBooleanExtra(IS_MMS, false)
        val pending = goAsync()
        ensureBackgroundThread {
            try {
                if (context.deleteMessage(messageId, isMms)) {
                    context.notificationManager.cancel(threadId.hashCode())
                    context.updateLastConversationMessage(threadId)
                    refreshMessages()
                    refreshConversations()
                } else {
                    runCatching { AgentSettings(context).recordStatus(
                        "Message deletion was not confirmed. Open SMS to review it.") }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
