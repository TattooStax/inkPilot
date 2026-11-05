package org.fossify.messages.inkpilot.smoke

import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.activities.AppLockActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.PROTECTION_PIN
import org.fossify.messages.R
import org.fossify.messages.activities.SplashActivity
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** The exported icon alias must instantiate the secure shell directly. */
@RunWith(AndroidJUnit4::class)
class LauncherAliasTest {
    @Test fun launcherAliasOpensLockedShellWithoutInheritedSplash() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.baseConfig
        assumeTrue(!config.isAppPasswordProtectionOn && config.appPasswordHash.isEmpty())
        val launcher = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals("app.inkpilot.android.activities.SplashActivity.Green", launcher.component?.className)
        val alias = context.packageManager.getActivityInfo(requireNotNull(launcher.component), 0)
        assertEquals(StudioHomeActivity::class.java.name, alias.targetActivity)
        val priorType = config.appProtectionType
        val priorUnlock = config.lastUnlockTimestampMs
        val homeMonitor = instrumentation.addMonitor(StudioHomeActivity::class.java.name, null, false)
        val splashMonitor = instrumentation.addMonitor(SplashActivity::class.java.name, null, false)
        val unlockMonitor = instrumentation.addMonitor(AppLockActivity::class.java.name, null, false)
        var home: StudioHomeActivity? = null
        var gate: AppLockActivity? = null
        try {
            instrumentation.runOnMainSync {
                config.appPasswordHash = MessageDigest.getInstance("SHA-1").digest("3947".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                config.appProtectionType = PROTECTION_PIN
                config.isAppPasswordProtectionOn = true
                context.appLockManager.lock()
            }
            context.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            home = homeMonitor.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            gate = unlockMonitor.waitForActivityWithTimeout(10_000) as AppLockActivity?
            assertNotNull("Launcher must instantiate StudioHomeActivity", home)
            assertNotNull("Locked launcher must show the app gate", gate)
            instrumentation.runOnMainSync {
                assertEquals("No inherited SplashActivity may run", 0, splashMonitor.hits)
                val shell = requireNotNull(home)
                assertEquals(0, shell.findViewById<ViewGroup>(R.id.inkpilot_canvas).childCount)
                assertTrue(shell.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(context.appLockManager.isLocked())
            }
        } finally {
            instrumentation.runOnMainSync {
                config.isAppPasswordProtectionOn = false
                config.appPasswordHash = ""
                config.appProtectionType = priorType
                context.appLockManager.unlock()
                config.lastUnlockTimestampMs = priorUnlock
                gate?.finish()
                home?.finish()
            }
            instrumentation.removeMonitor(homeMonitor)
            instrumentation.removeMonitor(splashMonitor)
            instrumentation.removeMonitor(unlockMonitor)
        }
    }
}
