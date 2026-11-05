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
import org.fossify.messages.services.HeadlessReplyAdmission

class DirectReplyReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val ready = prepare(context, intent) ?: return
        val pending = goAsync()
        ensureBackgroundThread {
            try {
                dispatch(context, intent, ready)
            } catch (e: Exception) {
                AgentSettings(context).recordStatus("Direct reply could not be confirmed. Open SMS to review.")
                context.showErrorToast(e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun prepare(context: Context, intent: Intent): DirectReplyReady? {
        val address = intent.getStringExtra(THREAD_NUMBER)?.takeIf(String::isNotBlank) ?: return null
        val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return null
        if (raw.isBlank()) return null
        val store = DirectReplyPendingStore(context)
        val admission = runCatching {
            HeadlessReplyAdmission.preserve(
                resolveThread = { intent.getLongExtra(THREAD_ID, 0L) },
                resolveSubscription = {
                    intent.getIntExtra(SUBSCRIPTION_ID, -1).takeIf { it >= 0 }
                        ?: context.config.savedSIMIdAtNumber(address)
                },
                persist = { thread, selected -> store.hold(thread, address, raw, selected) },
            )
        }.getOrElse {
            AgentSettings(context).recordStatus("Direct reply could not enter held review. Open SMS.")
            return null
        }
        val held = admission.held
        val threadId = held.threadId
        if (threadId == null || !admission.canSendFrom(context)) {
            runCatching { AgentSettings(context).recordStatus(
                "Direct reply held: SMS role, thread or SIM is unavailable. Review held replies.") }
            return null
        }
        val body = runCatching { context.removeDiacriticsIfNeeded(raw) }.getOrElse {
            runCatching { AgentSettings(context).recordStatus(
                "Direct reply held: text conversion failed. Review held replies.") }
            return null
        }
        return DirectReplyReady(address, threadId, body, held.id, held.subscriptionId)
    }

    private fun dispatch(context: Context, intent: Intent, ready: DirectReplyReady) {
        val binding = CarrierIngressPolicy.replyBinding(intent.dataString)
        val source = binding?.let { NotificationReplyIdentity.read(context, it) }?.takeIf(ready::matches)
        if (source == null) {
            AgentSettings(context).recordStatus("Direct reply held: native recipient or SIM could not be verified.")
            return
        }
        val active = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            .map { it.subscriptionId }.toSet()
        val subscriptionId = CarrierIngressPolicy.exactSubscription(source.subscriptionId, active)
            ?: run {
                AgentSettings(context).recordStatus(
                    "Direct reply held: its selected SIM is unavailable. Review held replies.")
                return
            }
        val outcome = context.sendMessageCompat(ready.body, listOf(source.recipient), subscriptionId,
            emptyList(), smsOnly = true)
        if (outcome !is DispatchOutcome.Accepted) {
            AgentSettings(context).recordStatus("Direct reply not confirmed. Review held replies before retrying.")
            return
        }
        DirectReplyPendingStore(context).complete(ready.heldId)
        showAccepted(context, ready, subscriptionId)
    }

    private fun showAccepted(context: Context, ready: DirectReplyReady, subscriptionId: Int) {
        var messageId = 0L
        context.getMessages(threadId = ready.threadId, includeScheduledMessages = false, limit = 1)
            .lastOrNull()?.let { message ->
                context.messagesDB.insertOrUpdate(message)
                messageId = message.id
                context.updateLastConversationMessage(ready.threadId)
            }
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(ready.address)
        val bitmap = context.getNotificationBitmap(photoUri)
        Handler(Looper.getMainLooper()).post {
            context.notificationHelper.showMessageNotification(
                messageId = messageId, isMms = false, address = ready.address, body = ready.body,
                threadId = ready.threadId, bitmap = bitmap, sender = null,
                alertOnlyOnce = true, subscriptionId = subscriptionId,
            )
        }
        context.markThreadMessagesRead(ready.threadId)
        context.conversationsDB.markRead(ready.threadId)
    }

    companion object { const val SUBSCRIPTION_ID = "inkpilot.reply.subscription_id" }
}

private data class DirectReplyReady(val address: String, val threadId: Long, val body: String,
    val heldId: String, val savedSubscription: Int?) {
    fun matches(source: NotificationReplyIdentity.Source): Boolean {
        if (source.threadId != threadId) return false
        if (source.recipient != address) return false
        return savedSubscription == null || savedSubscription == source.subscriptionId
    }
}

private fun HeadlessReplyAdmission.Admission<DirectReplyPendingStore.Held>.canSendFrom(context: Context): Boolean {
    if (!SmsRole.isHeld(context)) return false
    return savedSim != HeadlessReplyAdmission.SavedSim.LookupFailed
}
