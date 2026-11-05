package org.fossify.messages.receivers

import org.fossify.messages.messaging.recordScheduledCallback
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        val messageUri: Uri? = intent.data
        val messagingUtils = context.messagingUtils

        val type = if (receiverResultCode == Activity.RESULT_OK) {
            Sms.MESSAGE_TYPE_SENT
        } else {
            Sms.MESSAGE_TYPE_FAILED
        }
        val nativeRowVerified = messagingUtils.updateSmsMessageSendingStatus(messageUri, type)
        messagingUtils.maybeShowErrorToast(
            resultCode = receiverResultCode,
            errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE),
            noDefault = intent.getBooleanExtra(EXTRA_NO_DEFAULT, false)
        )
        return nativeRowVerified
    }

    override fun updateAppDatabase(
        context: Context, intent: Intent, receiverResultCode: Int, nativeRowVerified: Boolean,
    ) {
        context.recordScheduledCallback(intent, receiverResultCode == Activity.RESULT_OK, nativeRowVerified)
        if (!nativeRowVerified) return
        val messageUri = intent.data
        if (messageUri != null) {
            val messageId = messageUri.lastPathSegment?.toLongOrNull() ?: return
            val type = if (receiverResultCode == Activity.RESULT_OK) {
                Sms.MESSAGE_TYPE_SENT
            } else {
                showSendingFailedNotification(context, messageId)
                Sms.MESSAGE_TYPE_FAILED
            }
            context.messagesDB.updateType(messageId, type)
            refreshMessages()
            refreshConversations()
        }
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
