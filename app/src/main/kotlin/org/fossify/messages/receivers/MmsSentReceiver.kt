package org.fossify.messages.receivers

import org.fossify.messages.messaging.recordScheduledCallback
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.confirmMmsSent
import org.fossify.messages.messaging.recordMmsFailure
import android.app.Activity
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.messages.extensions.deleteMessage
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.messaging.getMmsSendingError
import java.io.File

/** Handles updating databases and states when a MMS message is sent. */
class MmsSentReceiver : SendStatusReceiver() {

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val uri = intent.getStringExtra(EXTRA_CONTENT_URI)?.let(Uri::parse)
        val originalResentMessageId = intent.getLongExtra(EXTRA_ORIGINAL_RESENT_MESSAGE_ID, -1L)
        val messageBox = if (receiverResultCode == Activity.RESULT_OK) {
            Telephony.Mms.MESSAGE_BOX_SENT
        } else {
            context.getMmsSendingError(
                receiverResultCode,
                intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            )?.let { msg ->
                context.toast(msg = msg, length = Toast.LENGTH_LONG)
            }
            Telephony.Mms.MESSAGE_BOX_FAILED
        }

        val values = ContentValues(1).apply {
            put(Telephony.Mms.MESSAGE_BOX, messageBox)
        }

        val providerUpdated = updateAndVerifyMmsRow(context, uri, values, messageBox)
        reconcileComposerMmsCallback(context, intent, receiverResultCode, providerUpdated)

        // In case of resent message, delete original to prevent duplication
        if (originalResentMessageId != -1L && receiverResultCode == Activity.RESULT_OK) {
            context.deleteMessage(originalResentMessageId, true)
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            File(filePath).delete()
        }
    }

    override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        context.recordScheduledCallback(intent, receiverResultCode == Activity.RESULT_OK)
        refreshMessages()
        refreshConversations()
    }

    companion object {
        private const val EXTRA_CONTENT_URI = "content_uri"
        private const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_ORIGINAL_RESENT_MESSAGE_ID = "original_message_id"
        const val EXTRA_COMPOSER_THREAD_ID = "inkpilot.composer.thread_id"
        const val EXTRA_COMPOSER_ATTEMPT_ID = "inkpilot.composer.attempt_id"
    }
}

/** mmslib supplies either a row URI or its newly persisted outbox row URI. */
internal fun isMmsCallbackRow(uri: Uri?): Boolean {
    if (uri?.scheme != "content" || uri.authority != Telephony.Mms.CONTENT_URI.authority) return false
    val parts = uri.pathSegments
    if (parts.lastOrNull()?.toLongOrNull()?.let { it > 0L } != true) return false
    return parts.size == 1 || (parts.size == 2 && parts[0] == "outbox")
}

/** Update the canonical row, then read back its terminal box before resolving a claim. */
private fun updateAndVerifyMmsRow(context: Context, callbackUri: Uri?, values: ContentValues, box: Int): Boolean {
    if (!isMmsCallbackRow(callbackUri)) return false
    val id = callbackUri!!.lastPathSegment!!.toLong()
    val row = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
    return try {
        if (context.contentResolver.update(row, values, null, null) <= 0) return false
        context.contentResolver.query(row, arrayOf(Telephony.Mms.MESSAGE_BOX), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) == box
        } ?: false
    } catch (e: SQLiteException) {
        context.showErrorToast(e)
        false
    } catch (e: SecurityException) {
        context.showErrorToast(e)
        false
    } catch (e: IllegalArgumentException) {
        context.showErrorToast(e)
        false
    }
}

/** A matching provider result is a local send verdict, never a delivery receipt. */
private fun reconcileComposerMmsCallback(context: Context, intent: Intent, resultCode: Int, providerUpdated: Boolean) {
    if (!providerUpdated) return
    val attemptId = intent.getStringExtra(MmsSentReceiver.EXTRA_COMPOSER_ATTEMPT_ID)
    val threadId = intent.getLongExtra(MmsSentReceiver.EXTRA_COMPOSER_THREAD_ID, Long.MIN_VALUE)
    if (attemptId.isNullOrBlank() || threadId == Long.MIN_VALUE) return
    val composer = ComposerStore(context)
    val reconciled = if (resultCode == Activity.RESULT_OK)
        composer.confirmMmsSent(threadId, attemptId)
    else composer.recordMmsFailure(threadId, attemptId)
    if (reconciled) runCatching { context.contentResolver.notifyChange(Telephony.Mms.CONTENT_URI, null) }
}
