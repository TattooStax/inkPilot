package org.fossify.messages.receivers

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.inkpilot.runtime.CarrierIngressPolicy

/** Re-read the native row instead of trusting a mutable RemoteInput PendingIntent's extras. */
internal object NotificationReplyIdentity {
    data class Source(val threadId: Long, val recipient: String, val subscriptionId: Int)

    fun read(context: Context, binding: CarrierIngressPolicy.ReplyBinding): Source? =
        if (binding.origin == "sms") sms(context, binding) else mms(context, binding)

    private fun sms(context: Context, binding: CarrierIngressPolicy.ReplyBinding): Source? {
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, binding.messageId)
        val columns = arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.TYPE)
        return context.contentResolver.query(uri, columns, null, null, null).use { cursor ->
            if (cursor?.moveToFirst() != true || cursor.getLong(0) != binding.threadId ||
                cursor.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX || cursor.isNull(2)) null
            else Source(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getInt(2))
                .takeIf { it.recipient.isNotBlank() && it.subscriptionId >= 0 }
        }
    }

    private fun mms(context: Context, binding: CarrierIngressPolicy.ReplyBinding): Source? {
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, binding.messageId)
        val message = context.getMMS(uri) ?: return null
        if (!message.isMMS || message.id != binding.messageId || message.threadId != binding.threadId ||
            message.type != Telephony.Mms.MESSAGE_BOX_INBOX) return null
        val sender = message.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: message.senderPhoneNumber
        return Source(message.threadId, sender, message.subscriptionId)
            .takeIf { it.recipient.isNotBlank() && it.subscriptionId >= 0 }
    }
}
