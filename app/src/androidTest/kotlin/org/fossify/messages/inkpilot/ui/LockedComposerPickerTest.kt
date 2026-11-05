package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.PROTECTION_PIN
import org.fossify.messages.messaging.ComposerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Returning from Android's picker cannot commit an image after the studio locks. */
@RunWith(AndroidJUnit4::class)
class LockedComposerPickerTest {
    @Test fun lockedResultIsDiscardedAndUnlockedComposerCanAttach() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.baseConfig
        assumeTrue(!config.isAppPasswordProtectionOn && config.appPasswordHash.isEmpty())
        val priorType = config.appProtectionType
        val priorUnlock = config.lastUnlockTimestampMs
        val route = StudioRoute.Compose(emptyList(), "Unsent test", emptyList())
        val store = ComposerStore(context)
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openRoute(route)
                    val attach = findText(activity.window.decorView, "Attach image (up to 1 MiB)")
                    assertTrue(attach?.performClick() == true)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    config.appPasswordHash = MessageDigest.getInstance("SHA-1").digest("3947".toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    config.appProtectionType = PROTECTION_PIN
                    config.isAppPasswordProtectionOn = true
                    context.appLockManager.lock()
                    assertTrue(context.appLockManager.isLocked())
                    deliverPickerResult(activity, Uri.parse("content://synthetic/locked-image"))
                    assertTrue(store.load(route.composerId)?.attachmentUris.orEmpty().isEmpty())
                    context.appLockManager.unlock()
                    config.isAppPasswordProtectionOn = false
                    val allowed = Uri.parse("content://synthetic/unlocked-image")
                    deliverPickerResult(activity, allowed)
                    assertEquals(listOf(allowed), store.load(route.composerId)?.attachmentUris)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            store.clear(route.composerId)
            config.isAppPasswordProtectionOn = false
            config.appPasswordHash = ""
            config.appProtectionType = priorType
            context.appLockManager.unlock()
            config.lastUnlockTimestampMs = priorUnlock
        }
    }

    private fun findText(view: View, value: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == value }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), value) }
        else -> null
    }

    private fun deliverPickerResult(activity: StudioHomeActivity, uri: Uri) {
        StudioHomeActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply {
            isAccessible = true
        }.invoke(activity, 7310, Activity.RESULT_OK, Intent().setData(uri))
    }
}
