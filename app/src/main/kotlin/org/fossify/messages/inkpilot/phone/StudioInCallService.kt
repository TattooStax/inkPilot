package org.fossify.messages.inkpilot.phone

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.util.IdentityHashMap

/** Telecom owns call lifecycle and ringing. This service supplies manual UI only. */
class StudioInCallService : InCallService() {
    companion object {
        var current: StudioInCallService? = null
            private set
        private const val NOTICE = 720
        private val callObservers = linkedSetOf<() -> Unit>()
        internal fun observeCalls(observer: () -> Unit) { callObservers.add(observer); observer() }
        internal fun stopObservingCalls(observer: () -> Unit) { callObservers.remove(observer) }
        internal fun notifyCallObservers() = callObservers.toList().forEach { observer ->
            runCatching { observer() }.onFailure { android.util.Log.w("InkPilotPhone", "Call display refresh failed") }
        }
    }
    private val tracked = linkedSetOf<Call>()
    private val actions = IncomingCallActionRegistry()
    private val tokens = IdentityHashMap<Call, String>()
    private var failedActionToken: String? = null
    val activeCalls: List<Call> get() = tracked.toList()
    var changed: (() -> Unit)? = null
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = update()
        override fun onDetailsChanged(call: Call, details: Call.Details) = update()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = update()
    }
    override fun onCreate() { super.onCreate(); current = this; notifyCallObservers() }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        tracked.add(call)
        tokens[call] = actions.register(object : IncomingCallTarget {
            override fun ringing() = tracked.any { it === call } && call.state == Call.STATE_RINGING
            override fun answer() = call.answer(VideoProfile.STATE_AUDIO_ONLY)
            override fun decline() = call.reject(false, null)
        })
        call.registerCallback(callback)
        update()
    }
    override fun onCallRemoved(call: Call) {
        revokeNotificationActions(call)
        if (failedActionToken == tokens[call]) failedActionToken = null
        call.unregisterCallback(callback); tracked.remove(call)
        tokens.remove(call)
        update()
        super.onCallRemoved(call)
    }
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        changed?.invoke()
    }
    override fun onBringToForeground(showDialpad: Boolean) {
        startActivity(Intent(this, ManualCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Manual and notification decisions invalidate the same one-use call token. */
    internal fun revokeNotificationActions(call: Call) {
        tokens[call]?.let { token ->
            actions.revoke(token)
            IncomingCallActionIntent.cancel(this, token)
        }
    }

    internal fun performNotificationAction(request: IncomingCallActionIntent.Request) {
        if (current !== this || tokens.entries.none { (call, token) ->
                tracked.any { it === call } && token == request.token }) return
        runCatching { actions.perform(request.token, request.decision) }
            .onFailure {
                failedActionToken = request.token
                android.util.Log.w("InkPilotPhone", "Notification call action failed")
            }
        IncomingCallActionIntent.cancel(this, request.token)
        update()
    }

    private fun update() {
        changed?.invoke()
        notifyCallObservers()
        val manager = getSystemService(NotificationManager::class.java)
        if (tracked.isEmpty()) { manager.cancel(NOTICE); return }
        // Identity is deliberately absent even from unlocked notifications; no stale lock-screen text.
        PhoneCallAlerts.createChannel(manager)
        val intent = Intent(this, ManualCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, NOTICE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ringing = tracked.any { it.state == Call.STATE_RINGING }
        val failed = failedActionToken?.takeIf { token ->
            tracked.any { call -> call.state == Call.STATE_RINGING && tokens[call] == token }
        }
        failedActionToken = failed
        val incomingToken = tracked.lastOrNull { call ->
            call.state == Call.STATE_RINGING && tokens[call]?.let(actions::live) == true
        }?.let(tokens::get)
        manager.notify(NOTICE, CallNotificationPresentation.build(this, pending,
            ringing, incomingToken, failed != null))
    }

    override fun onDestroy() {
        tokens.values.forEach { IncomingCallActionIntent.cancel(this, it) }
        tokens.clear(); actions.clear(); failedActionToken = null
        tracked.forEach { it.unregisterCallback(callback) }; tracked.clear()
        if (current === this) {
            getSystemService(NotificationManager::class.java).cancel(NOTICE)
            current = null
        }
        changed?.invoke(); changed = null
        notifyCallObservers()
        super.onDestroy()
    }
}
