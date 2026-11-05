package org.fossify.messages.inkpilot.smoke

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.SettingsActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.inkpilot.data.ReviewDraft
import org.fossify.messages.inkpilot.runtime.AgentRuntime
import org.fossify.messages.inkpilot.ui.InlineDraftReview
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioWork
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.Executor

/** Synthetic emulator gate. No carrier message, call, personal contact, or model output is created. */
@RunWith(AndroidJUnit4::class)
class OneShellAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tabs = listOf(
        "SMS" to R.id.inkpilot_tab_sms,
        "Phone" to R.id.inkpilot_tab_phone,
        "Contacts" to R.id.inkpilot_tab_contacts,
        "Studio" to R.id.inkpilot_tab_studio,
        "Calendar" to R.id.inkpilot_tab_calendar,
    )

    @Test fun newMessageAndSettingsStayInsideFiveTabShell() {
        requireDisposableEmulator()
        val legacy = listOf(ThreadActivity::class.java, NewConversationActivity::class.java,
            SettingsActivity::class.java).map { instrumentation.addMonitor(it.name, null, true) }
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                SmokeViews.clickDescription("SMS")
                SmokeViews.clickText("New message")
                scenario.onActivity { assertFooter(it) }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                SmokeViews.clickDescription("Open agent controls")
                SmokeViews.clickText("Messaging settings")
                scenario.onActivity { assertFooter(it, 0) }
            }
            assertEquals("Legacy activities must never replace the shell", 0, legacy.sumOf { it.hits })
        } finally {
            legacy.forEach(instrumentation::removeMonitor)
        }
    }

    @Test fun sideCardHasNoDailySelectionAndPhoneTabReturnsToPhoneCanvas() {
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.inkpilot_tab_phone).performClick()
                activity.findViewById<View>(R.id.inkpilot_menu).performClick()
            }
            SmokeViews.clickText("Soul & studio memory")
            scenario.onActivity { activity ->
                assertEquals(0, tabs.count { (_, id) -> activity.findViewById<View>(id).isSelected })
                assertEquals("Assistant", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
                activity.findViewById<View>(R.id.inkpilot_tab_phone).performClick()
                assertTrue(activity.findViewById<View>(R.id.inkpilot_tab_phone).isSelected)
                assertEquals("Phone", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
            }
        }
    }

    @Test fun landscapeSideCardScrollsAndPhysicalSoulTapWorks() = atLargeText { scenario ->
        scenario.onActivity {
            it.findViewById<View>(R.id.inkpilot_menu).performClick()
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        SmokeViews.await<Boolean>("landscape side card") {
            var ready = false
            scenario.onActivity { ready = it.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            if (ready) true else null
        }
        SmokeViews.await<Boolean>("old side card dismissed on rotation") {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val stale = root?.findAccessibilityNodeInfosByText("Soul & studio memory")
                ?.any { it.text?.toString() == "Soul & studio memory" && it.isVisibleToUser } == true
            if (!stale && root?.packageName?.toString() == instrumentation.targetContext.packageName) true else null
        }
        scenario.onActivity { it.findViewById<View>(R.id.inkpilot_menu).performClick() }
        // The dialog is resized after show(). Wait for its accessibility window to settle
        // before using screen coordinates; the first layout can report obsolete bounds.
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val touch = SmokeViews.await("stable Soul touch target") {
            val first = soulBounds() ?: return@await null
            SystemClock.sleep(100)
            first.takeIf { it == soulBounds() }
        }
        assertTrue("Soul has a visible touch target", touch.width() > 0 && touch.height() > 0)
        tapScreen(touch.centerX().toFloat(), touch.centerY().toFloat())
        SmokeViews.await<Boolean>("Assistant after physical Soul tap") {
            var opened = false
            scenario.onActivity { opened = it.findViewById<TextView>(R.id.inkpilot_title)?.text?.toString() == "Assistant" }
            if (opened) true else null
        }
        scenario.onActivity { it.findViewById<View>(R.id.inkpilot_menu).performClick() }
        val scroll = SmokeViews.await("scrollable side card") {
            findScroll(instrumentation.uiAutomation.rootInActiveWindow)?.takeIf { it.isScrollable }
        }
        repeat(4) {
            val close = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Close")
                ?.firstOrNull { it.text?.toString() == "Close" && it.isVisibleToUser }
            if (close != null) return@repeat
            assertTrue("Side card scrolls to lower actions", scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            instrumentation.waitForIdleSync()
        }
        SmokeViews.assertText("Close")
        SmokeViews.clickText("Close")
    }

    private fun findScroll(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString() == "android.widget.ScrollView") return node
        for (index in 0 until node.childCount) findScroll(node.getChild(index))?.let { return it }
        return null
    }

    private fun soulBounds(): Rect? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText("Soul & studio memory")
        ?.firstOrNull { it.text?.toString() == "Soul & studio memory" && it.isVisibleToUser && it.isClickable }
        ?.let { Rect().also(it::getBoundsInScreen) }

    /** One real screen touch, synchronously acknowledged by the input dispatcher. */
    private fun tapScreen(x: Float, y: Float) {
        val whenDown = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(whenDown, whenDown, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(whenDown, whenDown + 30, MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue("Soul touch down accepted", instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue("Soul touch up accepted", instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @Test fun calendarReminderHintOpensCalendarCanvas() {
        val context = instrumentation.targetContext
        ActivityScenario.launch<StudioHomeActivity>(Intent(context, StudioHomeActivity::class.java)
            .putExtra("inkpilot_panel", "calendar")).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Calendar", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
                assertTrue(activity.findViewById<View>(R.id.inkpilot_tab_calendar).isSelected)
                assertNotNull(activity.findViewById<View>(R.id.inkpilot_home))
            }
        }
    }

    @Test fun heldReplyEditorKeepsVisibleProgrammaticLabel() {
        requireDisposableEmulator()
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val draft = ReviewDraft("synthetic-a11y", "synthetic-event", 1L, "synthetic-contact",
                    "+12025550123", "Fixture", "Synthetic incoming text", "Synthetic held reply", 1L,
                    -1, 0L, false)
                val panel = InlineDraftReview(activity, StudioWork(activity, Executor { }), {}, { _, _ -> false }).view(draft) as ViewGroup
                val editor = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<EditText>().single()
                val label = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<TextView>()
                    .single { it.labelFor == editor.id }
                val expected = if (AgentRuntime.BOUNDED_INTENT_QUALIFIED) "Reply to review"
                    else "Historical text for staff context"
                assertEquals(expected, label.text.toString())
                assertEquals("Edit held reply", editor.hint.toString())
                assertTrue("Editor has a stable view ID for its label", editor.id != View.NO_ID)
            }
        }
    }

    @Test fun tabsRemainVisibleAtLargeTextAndLandscape() = atLargeText { scenario ->
        // Separate callsites preserve the original sequence and identify a redacted failing tab.
        clickDailyTab("SMS", R.id.inkpilot_tab_sms)
        scenario.onActivity { assertLargeTab(it, "SMS", R.id.inkpilot_tab_sms) }
        clickDailyTab("Phone", R.id.inkpilot_tab_phone)
        scenario.onActivity { assertLargeTab(it, "Phone", R.id.inkpilot_tab_phone) }
        val active = SmokeViews.await("active window after Phone") {
            instrumentation.uiAutomation.rootInActiveWindow
        }
        assertEquals("Phone canvas must leave the shell window active",
            instrumentation.targetContext.packageName, active.packageName?.toString())
        val contactsNode = active.findAccessibilityNodeInfosByViewId(
            "${instrumentation.targetContext.packageName}:id/inkpilot_tab_contacts").firstOrNull()
        assertNotNull("Contacts must remain in the active accessibility window", contactsNode)
        assertEquals("Contacts", contactsNode!!.contentDescription?.toString())
        assertTrue("Contacts remains an accessibility action", contactsNode.isClickable)
        clickDailyTab("Contacts", R.id.inkpilot_tab_contacts)
        scenario.onActivity { assertLargeTab(it, "Contacts", R.id.inkpilot_tab_contacts) }
        clickDailyTab("Studio", R.id.inkpilot_tab_studio)
        scenario.onActivity { assertLargeTab(it, "Studio", R.id.inkpilot_tab_studio) }
        clickDailyTab("Calendar", R.id.inkpilot_tab_calendar)
        scenario.onActivity { assertLargeTab(it, "Calendar", R.id.inkpilot_tab_calendar) }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        SmokeViews.await<Boolean>("landscape shell") {
            var ready = false
            scenario.onActivity {
                ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            if (ready) true else null
        }
        scenario.onActivity { assertFooter(it) }
    }

    /** Re-query the live tab when Android rejects a transient accessibility action. */
    private fun clickDailyTab(label: String, id: Int) {
        val context = instrumentation.targetContext
        val viewId = "${context.packageName}:id/${context.resources.getResourceEntryName(id)}"
        SmokeViews.await<Boolean>(label) {
            val node = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            if (node != null && node.contentDescription?.toString() == label && node.isVisibleToUser && node.isEnabled &&
                node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true else null
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertLargeTab(activity: StudioHomeActivity, label: String, id: Int) {
        assertFooter(activity)
        assertTrue("$label is selected", activity.findViewById<View>(id).isSelected)
    }

    private fun atLargeText(check: (ActivityScenario<StudioHomeActivity>) -> Unit) {
        requireDisposableEmulator()
        val original = shell("settings get system font_scale").ifBlank { "null" }
        try {
            shell("settings put system font_scale 1.5")
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                SmokeViews.await<Boolean>("1.5x font scale") {
                    var ready = false
                    scenario.onActivity { ready = it.resources.configuration.fontScale >= 1.5f }
                    if (ready) true else null
                }
                check(scenario)
            }
        } finally {
            if (original == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $original")
            assertEquals("Disposable AVD font scale restored", original, shell("settings get system font_scale"))
        }
    }

    private fun assertFooter(activity: StudioHomeActivity, selectedCount: Int = 1) {
        val footer = requireNotNull(activity.findViewById<View>(R.id.inkpilot_navigation))
        val canvas = requireNotNull(activity.findViewById<View>(R.id.inkpilot_canvas))
        val visible = Rect()
        assertTrue("Footer visible", footer.getGlobalVisibleRect(visible))
        assertEquals("Footer must not be clipped by the system bar", footer.height, visible.height())
        assertTrue("Canvas remains usable", canvas.height > 0)
        val paper = (requireNotNull(activity.findViewById<View>(R.id.inkpilot_home)).background as ColorDrawable).color
        assertEquals("Daily tab selection matches the current canvas", selectedCount,
            tabs.count { (_, id) -> activity.findViewById<View>(id).isSelected })
        tabs.forEach { (label, id) ->
            val item = requireNotNull(activity.findViewById<View>(id)) as ViewGroup
            val icon = item.getChildAt(0) as ImageView
            val text = item.getChildAt(1) as TextView
            assertEquals(label, item.contentDescription)
            assertTrue("$label is one focusable action", item.isFocusable && item.isClickable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, text.importantForAccessibility)
            assertTrue("$label has at least a 48dp touch target", item.height >= 48 * activity.resources.displayMetrics.density)
            val itemBounds = Rect()
            assertTrue("$label remains visible", item.getGlobalVisibleRect(itemBounds))
            assertEquals("$label target height must not be clipped", item.height, itemBounds.height())
            assertEquals("$label target width must not be clipped", item.width, itemBounds.width())
            val textBounds = Rect()
            assertTrue("$label text remains visible", text.getGlobalVisibleRect(textBounds))
            assertEquals("$label text height must not be clipped", text.height, textBounds.height())
            assertEquals("$label text width must not be clipped", text.width, textBounds.width())
            assertTrue("$label text stays inside its target", itemBounds.contains(textBounds))
            val layout = requireNotNull(text.layout)
            assertEquals("$label must not ellipsize or lose characters", text.text.length,
                layout.getLineEnd(layout.lineCount - 1))
            assertTrue("$label text contrast is at least 4.5:1",
                ColorUtils.calculateContrast(text.currentTextColor, paper) >= 4.5)
        }
    }

    private fun requireDisposableEmulator() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Only run on a disposable emulator" }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText().trim()
        }
}
