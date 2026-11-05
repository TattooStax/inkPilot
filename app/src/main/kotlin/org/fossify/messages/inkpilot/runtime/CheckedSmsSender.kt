package org.fossify.messages.inkpilot.runtime

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.fossify.messages.inkpilot.data.SendStatus
import org.fossify.messages.inkpilot.data.StudioRepository

/** Exact lookup: a thread draft remains sendable beyond the global review page. */
internal fun StudioRepository.heldDraftForSend(draftId: String) =
    draft(draftId)?.takeIf { it.status == "HELD" && it.canReview }

/** Staff approval is the only caller. No worker or model can reach this send action. */
internal class CheckedSmsSender(private val context: Context) {
    fun approveAndSend(draftId: String, editedBody: String, subscriptionId: Int): String {
        require(editedBody.isNotBlank() && editedBody.length <= 800) { "Replies must be between 1 and 800 characters." }
        check(org.fossify.messages.helpers.SmsRole.isHeld(context)) { "Make inkPilot the default SMS app first." }
        for (permission in listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)) {
            check(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) { "Allow SMS and phone permissions first." }
        }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        require(subscriptions.any { it.subscriptionId == subscriptionId }) { "Select an active business SIM." }
        @Suppress("DEPRECATION")
        val manager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        val parts = manager.divideMessage(editedBody.trim())
        require(parts.size in 1..32) { "This reply cannot be sent as SMS." }
        StudioRepository(context).use { repository ->
            val draft = repository.heldDraftForSend(draftId)
                ?: error("This draft is no longer waiting for approval.")
            val claim = repository.claimDraftForSend(draftId, draft.body, editedBody.trim(), subscriptionId)
                ?: error("This conversation changed or was paused. Review it again before sending.")
            var invoked = false
            var insertedId: Long? = null
            try {
                check(repository.prepareSendParts(draftId, parts.size)) { "Could not save send receipts." }
                val providerId = SmsProviderHistory.insert(context, claim)
                insertedId = providerId
                check(repository.attachProviderId(draftId, providerId)) { "Could not attach the native message record." }
                val sent = ArrayList(parts.indices.map { resultIntent(draftId, it) })
                invoked = true
                if (parts.size == 1) {
                    manager.sendTextMessage(claim.recipient, null, claim.body, sent[0], null)
                } else {
                    manager.sendMultipartTextMessage(claim.recipient, null, parts, sent, null)
                }
                SmsProviderHistory.refresh(context, claim.threadId)
                return "Submitted to the selected SIM. Waiting for carrier confirmation."
            } catch (_: Exception) {
                val state = if (invoked) SendStatus.UNKNOWN else SendStatus.FAILED
                // Provider cleanup must still run if studio storage is temporarily unavailable.
                if (!invoked && insertedId != null) runCatching { SmsProviderHistory.finish(context, claim.copy(providerId = insertedId), state) }
                runCatching {
                    repository.markSendResult(draftId, state, error = if (invoked) "Send acknowledgement unavailable" else "Preparation failed before carrier call")
                    repository.getSendClaim(draftId)?.let { SmsProviderHistory.finish(context, it, state) }
                }
                return if (invoked) "Send status is unknown. Check the conversation before sending anything again."
                else "The message was not sent. Check SMS permissions and try from the conversation."
            }
        }
    }

    private fun resultIntent(draftId: String, part: Int): PendingIntent {
        val intent = Intent(context, StudioSmsResultReceiver::class.java).apply {
            data = Uri.Builder().scheme("inkpilot").authority("send-result").appendPath(draftId).appendPath(part.toString()).build()
            putExtra("draft_id", draftId)
            putExtra("part", part)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
