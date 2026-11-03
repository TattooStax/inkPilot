package org.fossify.messages.inkpilot.runtime

import android.content.Context
import inkstax.sms.transport.InboundSms
import org.fossify.messages.inkpilot.data.StudioRepository

object AgentSmsIntake {
    fun record(context: Context, inbound: InboundSms, displayName: String) {
        val providerId = inbound.providerId?.takeIf { it > 0 } ?: return
        val threadId = inbound.threadId?.takeIf { it > 0 } ?: return
        StudioRepository(context).use {
            it.recordInbound(
                "sms:$providerId", threadId, inbound.sender, inbound.body,
                displayName, inbound.receivedAtMillis, inbound.subscriptionId,
            )
        }
        AgentRuntime.runPending(context)
    }
}
