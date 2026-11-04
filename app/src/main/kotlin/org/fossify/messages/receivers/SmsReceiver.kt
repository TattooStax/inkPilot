package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import inkstax.sms.transport.InboundSms
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message
import org.fossify.messages.inkpilot.runtime.CarrierIngressPolicy
import org.fossify.messages.inkpilot.runtime.FilteredSmsJournal
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.transport.LocalInboundSink
import org.fossify.messages.transport.holdAndDrainInboundArrival

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION || !SmsRole.isHeld(context)) return
        val pending = goAsync()
        val appContext = context.applicationContext

        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                // this is how it has always worked, but need to revisit this.
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val subject = parts.last().pseudoSubject.orEmpty()
                val status = parts.last().status
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }

                val date = parts.last().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                val subscriptionId = intent.getIntExtra("subscription", -1)
                fun skip(reason: String) = FilteredSmsJournal(appContext).use {
                    it.record(address, body, date, subscriptionId, reason)
                }
                if (isMessageFilteredOut(appContext, body)) { skip("keyword"); return@ensureBackgroundThread }
                if (appContext.isNumberBlocked(address)) { skip("blocked"); return@ensureBackgroundThread }
                if (appContext.baseConfig.blockUnknownNumbers) {
                    val result = appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                        SimpleContactsHelper(appContext).existsSync(address, it)
                    }
                    if (result == ContactLookupResult.NotFound) { skip("unknown_sender"); return@ensureBackgroundThread }
                }

                val threadId = appContext.getThreadId(address)

                handleMessageSync(
                    context = appContext,
                    address = address,
                    subject = subject,
                    body = body,
                    date = date,
                    threadId = threadId,
                    subscriptionId = subscriptionId,
                    status = status
                )
            } finally {
                pending.finish()
            }
        }
    }

    internal fun handleMessageSync(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int = 0,
        threadId: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        subscriptionId: Int,
        status: Int,
        nativeInsert: (Context) -> Long = {
            it.insertNewSMS(address, subject, body, date, read, threadId, type, subscriptionId)
        },
    ) {
        if (threadId <= 0) {
            FilteredSmsJournal(context).use { it.record(address, body, date, subscriptionId, "provider_failed") }
            return
        }
        val newMessageId = nativeInsert(context)
        if (CarrierIngressPolicy.nativeSource(newMessageId, threadId) == null) {
            FilteredSmsJournal(context).use { it.record(address, body, date, subscriptionId, "provider_failed") }
            org.fossify.messages.inkpilot.runtime.AgentSettings(context)
                .recordStatus("An SMS could not enter the native inbox. Open Agent to review it.")
            return
        }

        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)
        context.getConversations(threadId).firstOrNull()?.let { conv ->
            runCatching { context.insertOrUpdateConversation(conv) }
        }

        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )

        val message = Message(
            id = newMessageId,
            body = body,
            type = type,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId
        )

        context.messagesDB.insertOrUpdate(message)

        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(threadId, false)
        }

        refreshMessages()
        refreshConversations()
        context.showReceivedMessageNotification(
            messageId = newMessageId,
            isMms = false,
            address = address,
            senderName = senderName,
            body = body,
            threadId = threadId,
            bitmap = bitmap,
            subscriptionId = subscriptionId,
        )
        val inbound = InboundSms(
            sender = address, body = body, receivedAtMillis = date,
            subscriptionId = subscriptionId,
            providerId = newMessageId, threadId = threadId
        )
        // SMS-2-TOUCHPOINT 1/2: inbound SMS passes through the :transport seam's local
        // binding after local persistence, so later bindings share this entry point.
        runCatching { LocalInboundSink().onInbound(inbound) }
            .onFailure { org.fossify.messages.inkpilot.runtime.AgentSettings(context)
                .recordStatus("Optional bridge intake failed; native SMS and local draft are preserved.") }
        // A failure in local drafting must never interrupt native SMS delivery.
        runCatching { org.fossify.messages.inkpilot.runtime.AgentSmsIntake.record(context, inbound, senderName) }
            .onFailure {
                org.fossify.messages.inkpilot.runtime.AgentSettings(context)
                    .recordStatus("A message could not enter local drafting. Open Agent and retry.")
            }
        // SMS-25-TOUCHPOINT 1/4: a paired phone holds the arrival here, on this
        // receiver's background thread, and hands its delivery drain to
        // lifecycle-managed work, so the broadcast never waits on the network.
        context.holdAndDrainInboundArrival(inbound)
    }
}
