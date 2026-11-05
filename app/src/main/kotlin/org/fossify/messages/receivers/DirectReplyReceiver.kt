package org.fossify.messages.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.*
import org.fossify.messages.helpers.REPLY
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.inkpilot.runtime.AgentSettings
import org.fossify.messages.inkpilot.runtime.CarrierIngressPolicy
import org.fossify.messages.inkpilot.runtime.DirectReplyPendingStore
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.sendMessageCompat

class DirectReplyReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(THREAD_NUMBER)?.takeIf(String::isNotBlank) ?: return
        val threadId = intent.getLongExtra(THREAD_ID, 0L).takeIf { it > 0 } ?: return
        val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return
        if (!SmsRole.isHeld(context)) return
        val body = context.removeDiacriticsIfNeeded(raw)
        if (body.isBlank()) return
        val store = DirectReplyPendingStore(context)
        val selected = intent.getIntExtra(SUBSCRIPTION_ID, -1).takeIf { it >= 0 }
            ?: context.config.savedSIMIdAtNumber(address)
        val held = runCatching { store.hold(threadId, address, body, selected) }
            .getOrElse { return }
        val pending = goAsync()
        ensureBackgroundThread {
            try {
                val binding = CarrierIngressPolicy.replyBinding(intent.dataString)
                val source = binding?.let { NotificationReplyIdentity.read(context, it) }
                if (source == null || source.threadId != threadId ||
                    address != source.recipient ||
                    (held.subscriptionId != null && held.subscriptionId != source.subscriptionId)) {
                    AgentSettings(context).recordStatus("Direct reply held: native recipient or SIM could not be verified.")
                    return@ensureBackgroundThread
                }
                val active = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
                    .map { it.subscriptionId }.toSet()
                val subscriptionId = CarrierIngressPolicy.exactSubscription(source.subscriptionId, active)
                    ?: run {
                        AgentSettings(context).recordStatus("Direct reply held: its selected SIM is unavailable. Review held replies.")
                        return@ensureBackgroundThread
                    }
                val outcome = context.sendMessageCompat(body, listOf(source.recipient), subscriptionId, emptyList())
                if (outcome !is DispatchOutcome.Accepted) {
                    AgentSettings(context).recordStatus("Direct reply not confirmed. Review held replies before retrying.")
                    return@ensureBackgroundThread
                }
                store.complete(held.id)
                var messageId = 0L
                context.getMessages(threadId = threadId, includeScheduledMessages = false, limit = 1)
                    .lastOrNull()?.let { message ->
                        context.messagesDB.insertOrUpdate(message)
                        messageId = message.id
                        context.updateLastConversationMessage(threadId)
                    }
                val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
                val bitmap = context.getNotificationBitmap(photoUri)
                Handler(Looper.getMainLooper()).post {
                    context.notificationHelper.showMessageNotification(
                        messageId = messageId, isMms = false, address = address, body = body,
                        threadId = threadId, bitmap = bitmap, sender = null,
                        alertOnlyOnce = true, subscriptionId = subscriptionId,
                    )
                }
                context.markThreadMessagesRead(threadId)
                context.conversationsDB.markRead(threadId)
            } catch (e: Exception) {
                AgentSettings(context).recordStatus("Direct reply could not be confirmed. Open SMS to review.")
                context.showErrorToast(e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val SUBSCRIPTION_ID = "inkpilot.reply.subscription_id" }
}
