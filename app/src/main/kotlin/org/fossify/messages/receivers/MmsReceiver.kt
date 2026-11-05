package org.fossify.messages.receivers

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.bumptech.glide.Glide
import com.klinker.android.send_message.MmsReceivedReceiver
import inkstax.sms.transport.InboundDisposition
import inkstax.sms.transport.InboundKind
import inkstax.sms.transport.InboundSms
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.R
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message
import org.fossify.messages.inkpilot.runtime.CarrierIngressPolicy
import org.fossify.messages.transport.LocalInboundSink
import org.fossify.messages.transport.holdAndDrainInboundArrival

class MmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean {
        if (context.isNumberBlocked(address)) return true
        if (context.baseConfig.blockUnknownNumbers) {
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val result = SimpleContactsHelper(context).existsSync(address, privateCursor)
            return result == ContactLookupResult.NotFound
        }

        return false
    }

    override fun isContentBlocked(context: Context, content: String): Boolean {
        return isMessageFilteredOut(context, content)
    }

    override fun onMessageReceived(context: Context, messageUri: Uri?) {
        val uri = messageUri ?: return
        val id = CarrierIngressPolicy.mmsProviderId(uri.scheme, uri.authority, uri.pathSegments) ?: return
        runCatching { MmsInboundWorker.enqueue(context, id) }
            .onFailure { org.fossify.messages.inkpilot.runtime.AgentSettings(context)
                .recordStatus("MMS arrived, but intake scheduling failed. Open SMS to refresh the native inbox.") }
    }

    override fun onError(context: Context, error: String) {
        context.showErrorToast(context.getString(R.string.couldnt_download_mms))
    }

    internal fun handleMmsMessage(
        context: Context,
        mms: Message,
        size: Int,
        address: String
    ) {
        val glideBitmap = try {
            Glide.with(context)
                .asBitmap()
                .load(mms.attachment!!.attachments.first().getUri())
                .centerCrop()
                .into(size, size)
                .get()
        } catch (e: Exception) {
            null
        }


        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        context.showReceivedMessageNotification(
            messageId = mms.id,
            isMms = true,
            address = address,
            senderName = senderName,
            body = mms.body,
            threadId = mms.threadId,
            bitmap = glideBitmap,
            subscriptionId = mms.subscriptionId,
        )

        val inbound = InboundSms(
            sender = address, body = mms.body, receivedAtMillis = mms.date * 1000L,
            subscriptionId = mms.subscriptionId, kind = InboundKind.MMS,
            providerId = mms.id, threadId = mms.threadId
        )
        // SMS-2-TOUCHPOINT 2/2: MMS arrivals pass through the seam so V1 classifies them
        // NOT_FORWARDED instead of silently dropping them; media stays in the provider store.
        val inboundOutcome = LocalInboundSink().onInbound(inbound)
        // SMS-25-TOUCHPOINT 3/4: every arrival passes through the arrival caller, and
        // the caller's holding rule is what keeps an MMS arrival unheld — V1 does not
        // forward MMS, so this call holds nothing and drains nothing for it.
        context.holdAndDrainInboundArrival(inbound)
        if (inboundOutcome.disposition == InboundDisposition.NOT_FORWARDED) {
            // Surface the skipped forwarding visibly, distinct from a handled message.
            context.toast(id = R.string.mms_not_forwarded, length = Toast.LENGTH_LONG)
        }

        val conversation = context.getConversations(mms.threadId).firstOrNull() ?: return
        runCatching { context.insertOrUpdateConversation(conversation) }
        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(mms.threadId, false)
        }
        refreshMessages()
        refreshConversations()
    }
}
