package org.fossify.messages.messaging

import android.content.Context
import android.telephony.SmsMessage
import android.util.Patterns
import com.klinker.android.send_message.Settings
import inkstax.sms.transport.OutboundChannel
import inkstax.sms.transport.OutboundSms
import inkstax.sms.transport.TransportOutcome
import inkstax.sms.transport.outboundChannel
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.extensions.shortcutHelper
import org.fossify.messages.models.Attachment
import org.fossify.messages.transport.messageTransport
import org.fossify.messages.transport.showSmsTransportFailure

internal const val SMS_GROUP_REFUSAL = "Send one client at a time. Start a separate SMS for each contact."

/** The composer shows this before saving a send claim or touching the radio. */
internal fun Context.smsRecipientRejection(text: String, addresses: List<String>, hasAttachments: Boolean): String? {
    val settings = getSendMessageSettings()
    val channel = outboundChannel(hasAttachments, !hasAttachments && isLongMmsMessage(text, settings),
        addresses.size, settings.group)
    return SMS_GROUP_REFUSAL.takeIf { channel == OutboundChannel.SMS && addresses.distinct().size != 1 }
}

@Deprecated("TODO: Move/rewrite messaging config code into the app.")
fun Context.getSendMessageSettings(): Settings {
    val settings = Settings()
    settings.useSystemSending = true
    settings.deliveryReports = config.enableDeliveryReports
    settings.sendLongAsMms = config.sendLongMessageMMS
    settings.sendLongAsMmsAfter = 1
    settings.group = config.sendGroupMessageMMS
    return settings
}

fun Context.isLongMmsMessage(text: String, settings: Settings = getSendMessageSettings()): Boolean {
    val data = SmsMessage.calculateLength(text, false)
    val numPages = data.first()
    return numPages > settings.sendLongAsMmsAfter && settings.sendLongAsMms
}

/** Sends the message using the in-app SmsManager API wrappers if it's an SMS or using android-smsmms for MMS. */
fun Context.sendMessageCompat(
    text: String,
    addresses: List<String>,
    subId: Int?,
    attachments: List<Attachment>,
    messageId: Long? = null,
    mmsComposerClaim: MmsComposerClaim? = null
): DispatchOutcome {
    val settings = getSendMessageSettings()
    if (subId != null) {
        settings.subscriptionId = subId
    }

    val messagingUtils = messagingUtils
    // SMS-1-TOUCHPOINT 1/5: routing decision and SMS send go through the :transport seam
    // (inkstax.sms.transport); the long-message check keeps its previous short-circuit.
    val channel = outboundChannel(
        hasAttachments = attachments.isNotEmpty(),
        isLongMms = attachments.isEmpty() && isLongMmsMessage(text, settings),
        addressCount = addresses.size,
        sendGroupAsMms = settings.group
    )
    val result: DispatchOutcome
    if (channel == OutboundChannel.SMS && addresses.distinct().size != 1) {
        return DispatchOutcome.Rejected(SMS_GROUP_REFUSAL)
    }
    if (channel == OutboundChannel.MMS) {
        // Preflight all URIs before the first MMS handoff.
        for (attachment in attachments) {
            try {
                contentResolver.openInputStream(attachment.getUri())?.use { it.read() }
                    ?: return DispatchOutcome.Rejected("Attachment is unreadable")
            } catch (_: Exception) { return DispatchOutcome.Rejected("Attachment is unreadable") }
        }
        // we send all MMS attachments separately to reduces the chances of hitting provider MMS limit.
        if (attachments.isNotEmpty()) {
            val lastIndex = attachments.lastIndex
            if (attachments.size > 1) {
                for (i in 0 until lastIndex) {
                    val attachment = attachments[i]
                    messagingUtils.sendMmsMessage("", addresses, attachment, settings, messageId)
                }
            }

            val lastAttachment = attachments[lastIndex]
            messagingUtils.sendMmsMessage(text, addresses, lastAttachment, settings, messageId,
                mmsComposerClaim = mmsComposerClaim)
        } else {
            messagingUtils.sendMmsMessage(text, addresses, null, settings, messageId,
                mmsComposerClaim = mmsComposerClaim)
        }
        result = DispatchOutcome.Unknown(java.util.UUID.randomUUID().toString())
    } else {
        result = dispatchSms(text, addresses, settings, messageId)
    }
    ensureBackgroundThread {
        val threadId = getThreadId(addresses.toSet())
        shortcutHelper.reportSendMessageUsage(threadId)
    }
    return result
}

private fun Context.dispatchSms(text: String, addresses: List<String>, settings: Settings,
    messageId: Long?): DispatchOutcome {
    val outcome = messageTransport.sendSms(OutboundSms(
        text = text, addresses = addresses, subscriptionId = settings.subscriptionId,
        requireDeliveryReport = settings.deliveryReports, messageId = messageId,
    ))
    if (outcome is TransportOutcome.Failed) showSmsTransportFailure(outcome)
    return if (outcome is TransportOutcome.Sent) DispatchOutcome.Accepted()
        else DispatchOutcome.Unknown(java.util.UUID.randomUUID().toString())
}

/**
 * Check if a given "address" is a short code.
 * There's not much info available on these special numbers, even the wikipedia page (https://en.wikipedia.org/wiki/Short_code)
 * contains outdated information regarding max number of digits. The exact parameters for short codes can vary by country and by carrier.
 */
fun isShortCodeWithLetters(address: String): Boolean {
    if (Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
        // emails are not short codes: https://github.com/FossifyOrg/Messages/issues/115
        return false
    }

    return address.any { it.isLetter() }
}
