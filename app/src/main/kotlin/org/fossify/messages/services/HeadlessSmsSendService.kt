package org.fossify.messages.services

import android.app.Service
import android.content.Intent
import android.net.Uri
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.inkpilot.runtime.AgentSettings
import org.fossify.messages.inkpilot.runtime.CarrierIngressPolicy
import org.fossify.messages.inkpilot.runtime.DirectReplyPendingStore
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.sendMessageCompat

/** Android's RESPOND_VIA_MESSAGE entry. Persist human text; never guess a dual-SIM route. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        val number = CarrierIngressPolicy.respondRecipient(uri?.scheme, uri?.encodedSchemeSpecificPart?.let { Uri.decode(it) })
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() && it.length <= 16_000 }
        if (intent?.action != "android.intent.action.RESPOND_VIA_MESSAGE" || number == null || text == null || !SmsRole.isHeld(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val thread = getThreadId(number).takeIf { it > 0 } ?: run {
            stopSelf(startId); return START_NOT_STICKY
        }
        val savedId = config.savedSIMIdAtNumber(number)
        val held = runCatching { DirectReplyPendingStore(this).hold(thread, number, text, savedId) }
            .getOrElse { stopSelf(startId); return START_NOT_STICKY }
        ensureBackgroundThread {
            try {
                val ids = subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
                    .map { it.subscriptionId }.toSet()
                val selected = CarrierIngressPolicy.exactSubscription(held.subscriptionId, ids)
                if (selected == null) {
                    AgentSettings(this).recordStatus("Reply held: select an active SIM in SMS before sending.")
                } else {
                    val outcome = sendMessageCompat(text, listOf(number), selected, emptyList())
                    if (outcome is DispatchOutcome.Accepted) DirectReplyPendingStore(this).complete(held.id)
                    else AgentSettings(this).recordStatus("Reply not confirmed. Review held replies before retrying.")
                }
            } catch (_: Exception) {
                AgentSettings(this).recordStatus("Reply not confirmed. Review held replies before retrying.")
            } finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
}
