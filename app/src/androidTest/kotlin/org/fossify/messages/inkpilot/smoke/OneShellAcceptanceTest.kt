package org.fossify.messages.inkpilot.smoke

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
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
                val panel = InlineDraftReview(activity, StudioWork(activity, Executor { }), {}).view(draft) as ViewGroup
                val editor = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<EditText>().single()
                val label = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<TextView>()
                    .single { it.labelFor == editor.id }
                assertEquals("Reply to review", label.text.toString())
                assertEquals("Edit held reply", editor.hint.toString())
                assertTrue("Editor has a stable view ID for its label", editor.id != View.NO_ID)
            }
        }
    }

    @Test fun tabsRemainVisibleAtLargeTextAndLandscape() = atLargeText { scenario ->
        // Separate callsites preserve the original sequence and identify a redacted failing tab.
        SmokeViews.clickDescription("SMS")
        scenario.onActivity { assertLargeTab(it, "SMS", R.id.inkpilot_tab_sms) }
        SmokeViews.clickDescription("Phone")
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
        assertTrue("Contacts nav accessibility action",
            contactsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        scenario.onActivity { assertLargeTab(it, "Contacts", R.id.inkpilot_tab_contacts) }
        SmokeViews.clickDescription("Studio")
        scenario.onActivity { assertLargeTab(it, "Studio", R.id.inkpilot_tab_studio) }
        SmokeViews.clickDescription("Calendar")
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
