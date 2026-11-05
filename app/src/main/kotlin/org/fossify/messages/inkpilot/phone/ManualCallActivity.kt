package org.fossify.messages.inkpilot.phone

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.ui.StudioAppLock

class ManualCallActivity : Activity() {
    companion object {
        internal const val OUTGOING_REQUEST = "inkpilot.outgoing_call_request"
        private const val REQUESTED_AT = "outgoing.requested_at"
        private const val SAW_CALL = "outgoing.saw_call"
        private const val WAIT_FOR_TELECOM_MS = 8_000L
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var screen: ManualCallScreen
    private var requestedAt = 0L
    private var sawCall = false
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedAt = savedInstanceState?.getLong(REQUESTED_AT)
            ?: if (intent.getBooleanExtra(OUTGOING_REQUEST, false)) SystemClock.elapsedRealtime() else 0L
        sawCall = savedInstanceState?.getBoolean(SAW_CALL) ?: false
        protectWindow()
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        screen = ManualCallScreen(this, ::unlock, ::finish)
        setContentView(screen.view)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(OUTGOING_REQUEST, false)) {
            requestedAt = SystemClock.elapsedRealtime()
            sawCall = false
        }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(REQUESTED_AT, requestedAt)
        outState.putBoolean(SAW_CALL, sawCall)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        protectWindow()
        StudioInCallService.current?.changed = { runOnUiThread { render() } }
        render()
        screen.view.visibility = View.VISIBLE
        handler.postDelayed(refresh, 1000)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        StudioInCallService.current?.changed = null
        screen.view.visibility = View.INVISIBLE
        super.onPause()
    }
    private fun unlock() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        withSecureCallWindow(this) { StudioAppLock(this).onResume() }
    }
    private fun protectWindow() { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    private fun render() {
        protectWindow()
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked || appLockManager.isLocked()
        val service = StudioInCallService.current
        if (service?.activeCalls?.isNotEmpty() == true) sawCall = true
        val empty = when {
            requestedAt == 0L || sawCall -> "Call ended"
            SystemClock.elapsedRealtime() - requestedAt < WAIT_FOR_TELECOM_MS -> "Opening call controls…"
            else -> "Call controls unavailable. Check Android's active call controls."
        }
        screen.noCallLabel = empty
        screen.update(service?.let(::TelecomCallController), locked)
    }
}


/** Inherited authentication may change secure flags; call privacy always wins. */
internal fun withSecureCallWindow(activity: Activity, unlock: () -> Unit) {
    try { unlock() } finally { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
}
