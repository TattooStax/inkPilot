package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message

/** No process-local runnable owns dispatch. Commit the claim before any radio handoff. */
fun Context.dispatchScheduled(message: Message) {
    val key = message.key()
    val attachments = message.attachment?.attachments.orEmpty()
    val addresses = message.participants.getAddresses().distinct()
    // This app's scheduled provider-backed dispatch requires the SMS role.
    if (!SmsRole.isHeld(this) || addresses.isEmpty()) return
    val settings = getSendMessageSettings().apply { subscriptionId = message.subscriptionId }
    val smsParts = if (!message.isMMS) getSmsManager(message.subscriptionId).divideMessage(message.body).size else 0
    val count = if (message.isMMS) maxOf(1, attachments.size) else smsParts * addresses.size
    ScheduledVerdicts(this).use { journal ->
        if (!journal.claim(key, count)) return
        try {
            if (message.isMMS) {
                if (attachments.isEmpty()) {
                    messagingUtils.sendMmsMessage(message.body, addresses, null, settings, scheduledCallback = ScheduledCallback(key))
                } else attachments.forEachIndexed { index, attachment ->
                    messagingUtils.sendMmsMessage(if (index == attachments.lastIndex) message.body else "", addresses,
                        attachment, settings, scheduledCallback = ScheduledCallback(key, index))
                }
            } else addresses.forEachIndexed { index, address ->
                messagingUtils.sendSmsMessage(message.body, setOf(address), message.subscriptionId,
                    settings.deliveryReports, scheduledCallback = ScheduledCallback(key, index * smsParts))
            }
        } catch (_: Exception) { journal.unknown(key) }
    }
    refreshMessages()
}
