package org.fossify.messages.extensions

import android.content.Context
import org.fossify.commons.extensions.showErrorToast
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin

fun Context.deleteScheduledMessage(key: MessageKey) {
    try {
        require(key.origin == MessageOrigin.SCHEDULED_SMS || key.origin == MessageOrigin.SCHEDULED_MMS)
        messagesDB.delete(key)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}
