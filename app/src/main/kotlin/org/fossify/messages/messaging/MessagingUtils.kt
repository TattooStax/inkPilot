package org.fossify.messages.messaging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import android.widget.Toast
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.isPlainTextMimeType
import org.fossify.messages.extensions.smsSender
import org.fossify.messages.messaging.SmsException.Companion.ERROR_PERSISTING_MESSAGE
import org.fossify.messages.models.Attachment
import org.fossify.messages.receivers.MmsSentReceiver
import org.fossify.messages.receivers.nativeStatusUpdateVerified

class MessagingUtils(val context: Context) {

    /**
     * Insert an SMS to the given URI with thread_id specified.
     */
    private fun insertSmsMessage(
        subId: Int,
        dest: String,
        text: String,
        timestamp: Long,
        threadId: Long,
        status: Int = Sms.STATUS_NONE,
        type: Int = Sms.MESSAGE_TYPE_OUTBOX,
        messageId: Long? = null
    ): Uri {
        val response: Uri?
        val values = ContentValues().apply {
            put(Sms.ADDRESS, dest)
            put(Sms.DATE, timestamp)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.BODY, text)

            // insert subscription id only if it is a valid one.
            if (subId != Settings.DEFAULT_SUBSCRIPTION_ID) {
                put(Sms.SUBSCRIPTION_ID, subId)
            }

            if (status != Sms.STATUS_NONE) {
                put(Sms.STATUS, status)
            }
            if (type != Sms.MESSAGE_TYPE_ALL) {
                put(Sms.TYPE, type)
            }
            if (threadId != -1L) {
                put(Sms.THREAD_ID, threadId)
            }
        }

        try {
            if (messageId != null) {
                val selection = "${Sms._ID} = ?"
                val selectionArgs = arrayOf(messageId.toString())
                val count = context.contentResolver.update(
                    Sms.CONTENT_URI, values, selection, selectionArgs
                )
                response = if (count > 0) {
                    Uri.parse("${Sms.CONTENT_URI}/${messageId}")
                } else {
                    null
                }
            } else {
                response = context.contentResolver.insert(Sms.CONTENT_URI, values)
            }
        } catch (e: Exception) {
            throw SmsException(ERROR_PERSISTING_MESSAGE, e)
        }
        return response ?: throw SmsException(ERROR_PERSISTING_MESSAGE)
    }

    /** Send an SMS message given [text] and [addresses]. A [SmsException] is thrown in case any errors occur. */
    fun sendSmsMessage(
        text: String,
        addresses: Set<String>,
        subId: Int,
        requireDeliveryReport: Boolean,
        messageId: Long? = null,
        scheduledCallback: ScheduledCallback? = null
    ) {
        // A merged-address SENT row cannot be truthful until every recipient has a durable verdict.
        if (addresses.size != 1) throw SmsException(SmsException.ERROR_SENDING_MESSAGE)
        for (address in addresses) {
            val threadId = context.getThreadId(address)
            val messageUri = insertSmsMessage(
                subId = subId, dest = address, text = text,
                timestamp = System.currentTimeMillis(), threadId = threadId,
                messageId = messageId
            )
            try {
                context.smsSender.sendMessage(
                    subId = subId, destination = address, body = text, serviceCenter = null,
                    requireDeliveryReport = requireDeliveryReport, messageUri = messageUri,
                    scheduledCallback = scheduledCallback
                )
            } catch (e: Exception) {
                runCatching { SmsPartJournal(context).use { it.unknownRow(messageUri) } }
                updateSmsMessageSendingStatus(messageUri, Sms.Outbox.MESSAGE_TYPE_FAILED)
                throw e // propagate error to caller
            }
        }
    }

    /** A callback is confirmed only after its exact native row reflects the new type. */
    fun updateSmsMessageSendingStatus(messageUri: Uri?, type: Int): Boolean {
        val row = canonicalSmsCallbackRow(messageUri) ?: return false
        val values = ContentValues().apply { put(Sms.TYPE, type) }
        return try {
            nativeStatusUpdateVerified(type,
                update = { context.contentResolver.update(row, values, null, null) },
                read = { context.contentResolver.query(row, arrayOf(Sms.TYPE), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) else null
                } })
        } catch (e: Exception) {
            context.showErrorToast(e)
            false
        }
    }

    fun getSmsMessageFromDeliveryReport(intent: Intent): SmsMessage? {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        return SmsMessage.createFromPdu(pdu, format)
    }

    @Deprecated("TODO: Move/rewrite MMS code into the app.")
    fun sendMmsMessage(
        text: String,
        addresses: List<String>,
        attachment: Attachment?,
        settings: Settings,
        messageId: Long? = null,
        scheduledCallback: ScheduledCallback? = null,
        mmsComposerClaim: MmsComposerClaim? = null
    ) {
        val transaction = Transaction(context, settings)
        val message = Message(text, addresses.toTypedArray())

        if (attachment != null) {
            try {
                val uri = attachment.getUri()
                context.contentResolver.openInputStream(uri)?.use {
                    val bytes = it.readMmsAttachment(attachment, mmsComposerClaim)
                    val mimeType = if (attachment.mimetype.isPlainTextMimeType()) {
                        "application/txt"
                    } else {
                        attachment.mimetype
                    }
                    val name = attachment.filename
                    message.addMedia(bytes, mimeType, name, name)
                } ?: throw IllegalArgumentException("Attachment is unreadable")
            } catch (e: Exception) {
                throw e
            }
        }

        val mmsSentIntent = Intent(context, MmsSentReceiver::class.java)
        mmsSentIntent.putExtra(MmsSentReceiver.EXTRA_ORIGINAL_RESENT_MESSAGE_ID, messageId)
        mmsComposerClaim?.let { claim ->
            mmsSentIntent.putExtra(MmsSentReceiver.EXTRA_COMPOSER_THREAD_ID, claim.threadId)
            mmsSentIntent.putExtra(MmsSentReceiver.EXTRA_COMPOSER_ATTEMPT_ID, claim.attemptId)
        }
        scheduledCallback?.attach(mmsSentIntent, 0)
        mmsSentIntent.data = scheduledCallback?.let { Uri.parse(it.key.alarmIdentity() + "/" + it.offset) }
        transaction.setExplicitBroadcastForSentMms(mmsSentIntent)

        try {
            transaction.sendNewMessage(message)
        } catch (e: Exception) {
            throw e
        }
    }

    fun maybeShowErrorToast(resultCode: Int, errorCode: Int, noDefault: Boolean = false) {
        context.getSmsSendingError(resultCode, errorCode, noDefault)?.let { msg ->
            context.toast(msg = msg, length = Toast.LENGTH_LONG)
        }
    }

    companion object {
        const val ADDRESS_SEPARATOR = "|"
    }
}

private fun java.io.InputStream.readMmsAttachment(
    attachment: Attachment, composerClaim: MmsComposerClaim?,
): ByteArray = if (composerClaim == null) readBytes() else readBoundedMmsImage(attachment.mimetype)

/** Callback URIs must name a positive canonical SMS row, never a latest-outbox fallback. */
internal fun canonicalSmsCallbackRow(uri: Uri?): Uri? {
    if (uri?.scheme != "content" || uri.authority != Sms.CONTENT_URI.authority) return null
    if (uri.pathSegments.size != 1) return null
    val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return ContentUris.withAppendedId(Sms.CONTENT_URI, id)
}
