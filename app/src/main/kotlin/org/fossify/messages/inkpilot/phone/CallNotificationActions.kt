package org.fossify.messages.inkpilot.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/** A notification action is bound to one in-memory ringing call, never a phone number. */
internal interface IncomingCallTarget {
    fun ringing(): Boolean
    fun answer()
    fun decline()
}

internal enum class IncomingCallDecision(val intentAction: String, val path: String) {
    ANSWER("org.fossify.messages.inkpilot.phone.ANSWER", "answer"),
    DECLINE("org.fossify.messages.inkpilot.phone.DECLINE", "decline"),
}

/** Tokens are one-use and disappear on call removal, manual decision, or service teardown. */
internal class IncomingCallActionRegistry(private val newToken: () -> String = { UUID.randomUUID().toString() }) {
    private val targets = mutableMapOf<String, IncomingCallTarget>()
    private var serial = 0L

    fun register(target: IncomingCallTarget): String {
        val token = "${newToken()}-${serial++}"
        targets[token] = target
        return token
    }

    fun live(token: String) = token in targets
    fun revoke(token: String) { targets.remove(token) }
    fun clear() { targets.clear() }

    fun perform(token: String, decision: IncomingCallDecision): Boolean {
        val target = targets[token] ?: return false
        if (!target.ringing()) { targets.remove(token); return false }
        targets.remove(token)
        when (decision) {
            IncomingCallDecision.ANSWER -> target.answer()
            IncomingCallDecision.DECLINE -> target.decline()
        }
        return true
    }
}

/** The token is in the URI because PendingIntent identity ignores Intent extras. */
internal object IncomingCallActionIntent {
    private const val SCHEME = "inkpilot-call-action"
    private const val HOST = "incoming"
    private const val REQUEST_ANSWER = 723
    private const val REQUEST_DECLINE = 724
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT

    data class Request(val token: String, val decision: IncomingCallDecision)

    fun intent(context: Context, token: String, decision: IncomingCallDecision): Intent =
        Intent(context, CallNotificationActionReceiver::class.java)
            .setAction(decision.intentAction)
            .setData(Uri.Builder().scheme(SCHEME).authority(HOST)
                .appendPath(decision.path).appendPath(token).build())

    fun parse(intent: Intent?): Request? {
        val source = intent ?: return null
        val decision = IncomingCallDecision.entries.firstOrNull { it.intentAction == source.action } ?: return null
        val data = source.data ?: return null
        val parts = data.pathSegments
        if (data.scheme != SCHEME || data.host != HOST) return null
        if (data.query != null || data.fragment != null) return null
        if (parts.size != 2 || parts[0] != decision.path || parts[1].isBlank()) return null
        return Request(parts[1], decision)
    }

    fun pending(context: Context, token: String, decision: IncomingCallDecision): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode(decision), intent(context, token, decision), flags)

    fun cancel(context: Context, token: String) {
        IncomingCallDecision.entries.forEach { decision ->
            PendingIntent.getBroadcast(context, requestCode(decision), intent(context, token, decision),
                flags or PendingIntent.FLAG_NO_CREATE)?.cancel()
        }
    }

    private fun requestCode(decision: IncomingCallDecision) =
        if (decision == IncomingCallDecision.ANSWER) REQUEST_ANSWER else REQUEST_DECLINE
}

/** Only the active Telecom service can resolve the token to a tracked ringing Call. */
class CallNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val request = IncomingCallActionIntent.parse(intent) ?: return
        StudioInCallService.current?.performNotificationAction(request)
    }
}
