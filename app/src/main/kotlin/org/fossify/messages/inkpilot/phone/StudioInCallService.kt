package org.fossify.messages.inkpilot.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.messages.R

/** Telecom owns call lifecycle and ringing. This service supplies manual UI only. */
class StudioInCallService : InCallService() {
    companion object {
        var current: StudioInCallService? = null
            private set
        private const val NOTICE = 720
        private const val CHANNEL = "manual_calls"
    }
    private val tracked = linkedSetOf<Call>()
    val activeCalls: List<Call> get() = tracked.toList()
    var changed: (() -> Unit)? = null
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = update()
        override fun onDetailsChanged(call: Call, details: Call.Details) = update()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = update()
    }
    override fun onCreate() { super.onCreate(); current = this }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        tracked.add(call); call.registerCallback(callback)
        update()
    }
    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback); tracked.remove(call)
        update()
        super.onCallRemoved(call)
    }
    override fun onCallAudioStateChanged(audioState: CallAudioState) { super.onCallAudioStateChanged(audioState); changed?.invoke() }
    override fun onBringToForeground(showDialpad: Boolean) {
        startActivity(Intent(this, ManualCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun update() {
        changed?.invoke()
        val manager = getSystemService(NotificationManager::class.java)
        if (tracked.isEmpty()) { manager.cancel(NOTICE); return }
        // Identity is deliberately absent even from unlocked notifications; no stale lock-screen text.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Phone calls", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(this, ManualCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val incoming = tracked.any { it.state == Call.STATE_RINGING }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.inkpilot_nav_phone)
            .setContentTitle(if (incoming) "Incoming phone call" else "Phone call")
            .setContentText("Tap for manual call controls")
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(pending)
            .setFullScreenIntent(pending, incoming).build()
        manager.notify(NOTICE, notification)
    }
    override fun onDestroy() {
        tracked.forEach { it.unregisterCallback(callback) }; tracked.clear()
        getSystemService(NotificationManager::class.java).cancel(NOTICE)
        changed?.invoke(); changed = null; current = null
        super.onDestroy()
    }
}
