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
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (intent?.action != "android.intent.action.RESPOND_VIA_MESSAGE" || number == null || text == null || !SmsRole.isHeld(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val admission = runCatching {
            HeadlessReplyAdmission.preserve(
                resolveThread = { getThreadId(number) },
                resolveSubscription = { config.savedSIMIdAtNumber(number) },
                persist = { thread, savedId ->
                    DirectReplyPendingStore(this).hold(thread, number, text, savedId)
                },
            )
        }.getOrElse {
            runCatching { AgentSettings(this).recordStatus("Reply could not enter held review. Open SMS.") }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val held = admission.held
        if (held.threadId == null) {
            runCatching { AgentSettings(this).recordStatus(
                "Reply held: native thread is unavailable. Review held replies in Agent."
            ) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (admission.savedSim == HeadlessReplyAdmission.SavedSim.LookupFailed) {
            runCatching { AgentSettings(this).recordStatus(
                "Reply held: saved SIM lookup failed. Review held replies in Agent."
            ) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureBackgroundThread {
            try {
                val ids = subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
                    .map { it.subscriptionId }.toSet()
                val selected = admission.exactSubscription(ids)
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
