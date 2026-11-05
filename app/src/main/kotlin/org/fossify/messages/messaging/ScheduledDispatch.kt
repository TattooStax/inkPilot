package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message

/** Reload and commit the claim before handoff. Never dispatch a stale editor/alarm snapshot. */
fun Context.dispatchScheduled(snapshot: Message, fromAlarm: Boolean = false,
                              nowMillis: Long = System.currentTimeMillis()): DispatchOutcome {
    val key = snapshot.key()
    ScheduledVerdicts(this).use { journal ->
        var rejection = "This send is already claimed. Check the conversation before sending again."
        val message = journal.claimPrepared(key) {
            val current = messagesDB.get(key) ?: return@claimPrepared null
            val problem = scheduledClaimProblem(current, fromAlarm, nowMillis)
            if (problem != null) {
                rejection = problem
                return@claimPrepared null
            }
            val addresses = current.participants.getAddresses().distinct()
            val attachments = current.attachment?.attachments.orEmpty()
            val parts = if (current.isMMS) maxOf(1, attachments.size)
                else getSmsManager(current.subscriptionId).divideMessage(current.body).size * addresses.size
            current to parts
        } ?: return DispatchOutcome.Rejected(rejection)
        try {
            handoffScheduled(message, key)
        } catch (_: Exception) {
            journal.unknown(key)
            return DispatchOutcome.Unknown(key.alarmIdentity())
        }
    }
    refreshMessages()
    return DispatchOutcome.Accepted()
}

private fun Context.handoffScheduled(message: Message, key: MessageKey) {
    val attachments = message.attachment?.attachments.orEmpty()
    val addresses = message.participants.getAddresses().distinct()
    val settings = getSendMessageSettings().apply { subscriptionId = message.subscriptionId }
    if (message.isMMS) {
        if (attachments.isEmpty()) {
            messagingUtils.sendMmsMessage(message.body, addresses, null, settings, scheduledCallback = ScheduledCallback(key))
        } else attachments.forEachIndexed { index, attachment ->
            messagingUtils.sendMmsMessage(if (index == attachments.lastIndex) message.body else "", addresses,
                attachment, settings, scheduledCallback = ScheduledCallback(key, index))
        }
    } else {
        val parts = getSmsManager(message.subscriptionId).divideMessage(message.body).size
        addresses.forEachIndexed { index, address ->
            messagingUtils.sendSmsMessage(message.body, setOf(address), message.subscriptionId,
                settings.deliveryReports, scheduledCallback = ScheduledCallback(key, index * parts))
        }
    }
}

private fun Context.scheduledClaimProblem(current: Message, fromAlarm: Boolean, nowMillis: Long): String? {
    if (fromAlarm && current.millis() > nowMillis) return "This edited message is scheduled for later."
    val addresses = current.participants.getAddresses().distinct()
    if (!current.isMMS && addresses.size != 1) return SMS_GROUP_REFUSAL
    return scheduledPreflight(current.subscriptionId, addresses.isNotEmpty(), SmsRole.isHeld(this)) {
        scheduledMediaReadable(current)
    }
}

private fun Context.scheduledMediaReadable(message: Message): Boolean =
    message.attachment?.attachments.orEmpty().all { attachment ->
        runCatching {
            contentResolver.openInputStream(attachment.getUri())?.use { it.read(); true } == true
        }.getOrDefault(false)
    }
