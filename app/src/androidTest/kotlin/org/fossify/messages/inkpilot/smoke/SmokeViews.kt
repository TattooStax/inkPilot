package org.fossify.messages.inkpilot.smoke

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.File

internal object SmokeViews {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    fun <T : Any> await(description: String, timeoutMillis: Long = 20_000, read: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            read()?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    fun clickDescription(description: String) {
        val node = await(description) {
            find(automation.rootInActiveWindow) { it.contentDescription?.toString() == description && it.isClickable }
        }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    fun clickText(text: String) {
        val node = await(text) {
            find(automation.rootInActiveWindow) { it.text?.toString() == text && it.isClickable }
        }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    fun assertText(text: String) {
        await("visible text: $text") {
            find(automation.rootInActiveWindow) { it.isVisibleToUser && it.text?.toString() == text }
        }
    }

    fun capture(file: File) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val image = requireNotNull(automation.takeScreenshot()) { "Android did not provide a screenshot" }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun find(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matches(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), matches)?.let { return it }
        return null
    }
}
