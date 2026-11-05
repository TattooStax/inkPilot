package org.fossify.messages.inkpilot.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only the active Telecom service can resolve the token to a tracked ringing Call. */
class CallNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val request = IncomingCallActionIntent.parse(intent) ?: return
        StudioInCallService.current?.performNotificationAction(request)
    }
}
