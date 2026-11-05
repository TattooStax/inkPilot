package org.fossify.messages.inkpilot.phone

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.ui.StudioAppLock

class ManualCallActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var screen: ManualCallScreen
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectWindow()
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        screen = ManualCallScreen(this, ::unlock, ::finish)
        setContentView(screen.view)
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
        screen.update(StudioInCallService.current?.let(::TelecomCallController), locked)
    }
}


/** Inherited authentication may change secure flags; call privacy always wins. */
internal fun withSecureCallWindow(activity: Activity, unlock: () -> Unit) {
    try { unlock() } finally { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
}
