package org.fossify.messages.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import inkstax.sms.transport.isLocalCompanionEndpoint
import org.fossify.messages.helpers.SmsRole

/** This protected signal carries no message or credentials. The Worker consults its paired queue. */
class LocalPickupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "app.inkstax.host.action.OUTBOUND_SMS_READY" || !SmsRole.isHeld(context)) return
        val credential = context.storedDeviceCredential() ?: return
        if (!isLocalCompanionEndpoint(credential.endpointBase)) return
        val delivery = goAsync()
        try {
            val result = context.enqueueOutboundPickup()?.result
            if (result == null) delivery.finish() else result.addListener({ delivery.finish() }, Runnable::run)
        } catch (unavailable: RuntimeException) {
            delivery.finish() // The approved item is still durable at the host.
        }
    }
}
