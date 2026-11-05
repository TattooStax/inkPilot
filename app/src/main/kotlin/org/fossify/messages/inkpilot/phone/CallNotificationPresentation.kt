package org.fossify.messages.inkpilot.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.os.Build
import org.fossify.messages.R

/** Public lock-screen copy contains only generic call text; actions exist for a live token. */
internal object CallNotificationPresentation {
    fun build(context: Context, content: PendingIntent, ringing: Boolean, token: String?,
        failed: Boolean = false): Notification {
        val builder = Notification.Builder(context, PhoneCallAlerts.CHANNEL)
            .setSmallIcon(R.drawable.inkpilot_nav_phone)
            .setContentTitle(if (ringing) "Incoming phone call" else "Phone call")
            .setContentText(if (failed) "Call action failed. Tap for manual controls"
                else "Tap for manual call controls")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(if (ringing) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(content)
        if (token != null) {
            val answer = IncomingCallActionIntent.pending(context, token, IncomingCallDecision.ANSWER)
            val decline = IncomingCallActionIntent.pending(context, token, IncomingCallDecision.DECLINE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setStyle(Notification.CallStyle.forIncomingCall(
                    Person.Builder().setName("Phone call").build(), decline, answer))
            } else {
                builder.addAction(R.drawable.inkpilot_nav_phone, "Answer", answer)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
            }
            builder.setFullScreenIntent(content, true)
        }
        return builder.build()
    }
}
