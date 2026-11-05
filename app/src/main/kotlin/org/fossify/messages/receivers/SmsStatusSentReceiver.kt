package org.fossify.messages.receivers

import org.fossify.messages.messaging.recordScheduledCallback
import org.fossify.messages.messaging.processSmsSentPart
import org.fossify.messages.messaging.exactSmsRowType
import org.fossify.messages.messaging.SmsPartCallback
import org.fossify.messages.messaging.SmsPartJournal
import org.fossify.messages.messaging.SmsPartState
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.getMessageRecipientAddress
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.extensions.notificationHelper
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages

/** Handles updating databases and states when a SMS message is sent. */
class SmsStatusSentReceiver : SendStatusReceiver() {

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int): Boolean {
        val result = context.processSmsSentPart(intent.data, receiverResultCode == Activity.RESULT_OK)
        context.messagingUtils.maybeShowErrorToast(
            resultCode = receiverResultCode,
            errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE),
            noDefault = intent.getBooleanExtra(EXTRA_NO_DEFAULT, false)
        )
        return result.verified
    }

    override fun updateAppDatabase(
        context: Context, intent: Intent, receiverResultCode: Int, nativeRowVerified: Boolean,
    ) {
        val callback = SmsPartCallback.parse(intent.data)
        var aggregateState: SmsPartState? = null
        if (nativeRowVerified && callback != null) {
            var nativeType = runCatching { context.exactSmsRowType(callback.rowId) }.getOrNull()
            val state = nativeType?.let { SmsPartJournal(context).use { journal ->
                journal.state(callback.rowId, exactNativeType = { nativeType })
            } }
            aggregateState = state
            nativeType = context.correctNativeConflict(callback.rowId, state, nativeType)
            val settledType = when (state) {
                SmsPartState.SENT -> Sms.MESSAGE_TYPE_SENT
                SmsPartState.PARTIAL_FAILED, SmsPartState.FAILED, SmsPartState.UNKNOWN ->
                    Sms.MESSAGE_TYPE_FAILED.takeIf { nativeType == Sms.MESSAGE_TYPE_FAILED }
                else -> null
            }
            if (settledType != null) {
                context.messagesDB.updateType(callback.rowId, settledType)
                if (settledType == Sms.MESSAGE_TYPE_FAILED) showSendingFailedNotification(context, callback.rowId)
            }
        }
        val aggregateVerified = nativeRowVerified && aggregateState != null && aggregateState != SmsPartState.UNKNOWN
        context.recordScheduledCallback(intent, receiverResultCode == Activity.RESULT_OK, aggregateVerified)
        refreshMessages()
        refreshConversations()
    }

    private fun Context.correctNativeConflict(rowId: Long, state: SmsPartState?, nativeType: Int?): Int? {
        if (state != SmsPartState.UNKNOWN || nativeType != Sms.MESSAGE_TYPE_SENT) return nativeType
        val row = ContentUris.withAppendedId(Sms.CONTENT_URI, rowId)
        return if (messagingUtils.updateSmsMessageSendingStatus(row, Sms.MESSAGE_TYPE_FAILED))
            Sms.MESSAGE_TYPE_FAILED else nativeType
    }

    private fun showSendingFailedNotification(context: Context, messageId: Long) {
        Handler(Looper.getMainLooper()).post {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@post
            }
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ensureBackgroundThread {
                val address = context.getMessageRecipientAddress(messageId)
                val threadId = context.getThreadId(address)
                val recipientName = context.getNameFromAddress(address, privateCursor)
                context.notificationHelper.showSendingFailedNotification(recipientName, threadId)
            }
        }
    }
}
