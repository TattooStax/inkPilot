package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Message

enum class ScheduledQueueResult { QUEUED, POST_FAILED, CLAIMED }

/** The persisted row is recoverable even when posting the alarm throws or the process dies. */
internal fun persistThenPost(persist: () -> Boolean, post: () -> Unit): ScheduledQueueResult {
    if (!persist()) return ScheduledQueueResult.CLAIMED
    return try { post(); ScheduledQueueResult.QUEUED } catch (_: Exception) { ScheduledQueueResult.POST_FAILED }
}

fun Context.queueScheduled(message: Message, previousKey: MessageKey? = null,
    post: (Message) -> Unit = { scheduleMessage(it) }): ScheduledQueueResult =
    ScheduledVerdicts(this).use { journal ->
        persistThenPost(persist = {
            journal.editQueued(listOfNotNull(previousKey, message.key())) {
                messagesDB.insertOrUpdate(message)
                if (previousKey != null && previousKey != message.key()) messagesDB.delete(previousKey)
            }
        }, post = {
            if (previousKey != null && previousKey != message.key()) cancelScheduleSendPendingIntent(previousKey)
            post(message)
        })
    }

fun Context.scheduledStatus(key: MessageKey): String = ScheduledVerdicts(this).use { journal ->
    when (journal.state(key)) {
        "QUEUED" -> "Scheduled. Edit, send now, or cancel."
        "FAILED" -> "Carrier rejected all parts. Review the conversation before composing again."
        "CONFIRMED" -> "Carrier confirmed all parts."
        else -> "Send status unknown or pending. Check the conversation; do not resend automatically."
    }
}
