package org.fossify.messages.receivers

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.messaging.canonicalSmsCallbackRow
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.helpers.refreshMessages

/** Handles updating databases and states when a sent SMS message is delivered. */
class SmsStatusDeliveredReceiver : SendStatusReceiver() {

    private var status: Int = Sms.Sent.STATUS_NONE

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int): Boolean {
        val messageUri: Uri? = intent.data
        val smsMessage = context.messagingUtils.getSmsMessageFromDeliveryReport(intent) ?: return false

        status = try {
            normalizedDeliveryStatus(smsMessage.status, intent.getStringExtra("format"))
        } catch (_: NullPointerException) {
            // Some platform SmsMessage wrappers can be null despite a non-null outer object.
            return false
        }

        return updateSmsStatusAndDateSent(context, messageUri, System.currentTimeMillis())
    }

    private fun updateSmsStatusAndDateSent(context: Context, messageUri: Uri?, timeSentInMillis: Long = -1L): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            if (status != Sms.Sent.STATUS_NONE) {
                put(Sms.Sent.STATUS, status)
            }
            put(Sms.Sent.DATE_SENT, timeSentInMillis)
        }

        val row = canonicalSmsCallbackRow(messageUri) ?: return false
        if (status == Sms.Sent.STATUS_NONE) return false
        return try {
            nativeStatusUpdateVerified(status,
                update = { resolver.update(row, values, null, null) },
                read = { resolver.query(row, arrayOf(Sms.Sent.STATUS), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) else null
                } })
        } catch (_: Exception) { false }
    }

    override fun updateAppDatabase(
        context: Context, intent: Intent, receiverResultCode: Int, nativeRowVerified: Boolean,
    ) {
        if (!nativeRowVerified) return
        val messageUri: Uri? = intent.data
        if (messageUri != null) {
            val messageId = messageUri.lastPathSegment?.toLongOrNull() ?: return
            if (status != Sms.Sent.STATUS_NONE) {
                context.messagesDB.updateStatus(messageId, status)
            }
            refreshMessages()
        }
    }
}

private const val CDMA_ERROR_CLASS_SHIFT = 24
private const val CDMA_STATUS_CODE_SHIFT = 16
private const val CDMA_ERROR_CLASS_MASK = 0x03
private const val CDMA_STATUS_CODE_MASK = 0x3f
private const val CDMA_STATUS_DELIVERED = 0x02
private const val CDMA_PERMANENT_ERROR_CLASS = 3

/** Match CDMA delivery report bits to the SMS provider's status vocabulary. */
internal fun normalizedDeliveryStatus(rawStatus: Int, format: String?): Int {
    if (format != "3gpp2") return rawStatus
    val errorClass = rawStatus shr CDMA_ERROR_CLASS_SHIFT and CDMA_ERROR_CLASS_MASK
    val statusCode = rawStatus shr CDMA_STATUS_CODE_SHIFT and CDMA_STATUS_CODE_MASK
    return when (errorClass) {
        0 -> if (statusCode == CDMA_STATUS_DELIVERED) Sms.STATUS_COMPLETE else Sms.STATUS_PENDING
        CDMA_PERMANENT_ERROR_CLASS -> Sms.STATUS_FAILED
        else -> Sms.STATUS_PENDING
    }
}
