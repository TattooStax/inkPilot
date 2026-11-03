package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.activities.AppLockActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.PROTECTION_PIN
import org.fossify.messages.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Uses only a synthetic PIN in a fresh emulator profile; never replaces an existing credential. */
@RunWith(AndroidJUnit4::class)
class StudioAppLockTest {
    @Test
    fun lockedNotificationEntryUsesExistingUnlockScreenAndHidesStudioCanvas() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.baseConfig
        assumeFalse("Use a fresh emulator profile", config.isAppPasswordProtectionOn)
        assumeTrue("Never replace an existing credential", config.appPasswordHash.isEmpty())
        val previousType = config.appProtectionType
        val previousTimestamp = config.lastUnlockTimestampMs
        val monitor = instrumentation.addMonitor(AppLockActivity::class.java.name, null, false)
        var home: StudioHomeActivity? = null
        var unlock: Activity? = null
        try {
            instrumentation.runOnMainSync {
                config.appPasswordHash = MessageDigest.getInstance("SHA-1").digest("3947".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                config.appProtectionType = PROTECTION_PIN
                config.isAppPasswordProtectionOn = true
                context.appLockManager.lock()
            }
            home = instrumentation.startActivitySync(Intent(context, StudioHomeActivity::class.java)
                .putExtra("inkpilot_panel", "drafts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StudioHomeActivity
            unlock = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull("The inherited unlock activity must open", unlock)
            instrumentation.runOnMainSync {
                val canvas = requireNotNull(home).findViewById<ViewGroup>(R.id.inkpilot_canvas)
                assertEquals("No client, memory, or draft view is rendered before unlock", 0, canvas.childCount)
                assertTrue("Locked studio content must not enter screenshots/recents",
                    requireNotNull(home).window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(context.appLockManager.isLocked())
            }
        } finally {
            instrumentation.runOnMainSync {
                config.isAppPasswordProtectionOn = false
                config.appPasswordHash = ""
                config.appProtectionType = previousType
                context.appLockManager.unlock()
                config.lastUnlockTimestampMs = previousTimestamp
                unlock?.finish()
                home?.finish()
            }
            instrumentation.removeMonitor(monitor)
        }
    }
}
