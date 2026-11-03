package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.view.WindowManager
import org.fossify.commons.activities.maybeLaunchAppUnlockActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig

/** Reuse the inherited authentication flow without BaseSimpleActivity's fork-branding warning. */
internal class StudioAppLock(private val activity: Activity) {
    fun onResume(): Boolean {
        if (activity.baseConfig.isAppPasswordProtectionOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (!activity.appLockManager.isLocked()) return true
        activity.maybeLaunchAppUnlockActivity(UNLOCK_REQUEST)
        return false
    }

    companion object { private const val UNLOCK_REQUEST = 7304 }
}
