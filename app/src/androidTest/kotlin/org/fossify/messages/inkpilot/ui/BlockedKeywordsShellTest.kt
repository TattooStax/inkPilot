package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.R
import org.fossify.messages.activities.ManageBlockedKeywordsActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Keyword add/edit/remove stays in the native shell and changes the receiver's actual filter set. */
@RunWith(AndroidJUnit4::class)
class BlockedKeywordsShellTest {
    @Test fun manageKeywordsWithoutOpeningLegacyActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlock = context.baseConfig.lastUnlockTimestampMs
        val original = context.config.blockedKeywords.toSet()
        val keyword = "synthetic-${UUID.randomUUID()}"
        val replacement = "$keyword-edited"
        val legacy = instrumentation.addMonitor(ManageBlockedKeywordsActivity::class.java.name, null, true)
        context.appLockManager.unlock()
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { it.openRoute(StudioRoute.Settings("messaging")) }
                SmokeViews.clickText("Blocked keywords")
                scenario.onActivity { assertShell(it) }
                SmokeViews.clickText("Add keyword")
                setKeyword(context, keyword)
                scenario.recreate()
                assertEquals(keyword, keywordNode(context).text.toString())
                SmokeViews.clickText("Save")
                assertTrue(context.config.blockedKeywords.contains(keyword))
                SmokeViews.clickText("Edit $keyword")
                setKeyword(context, replacement)
                SmokeViews.clickText("Save")
                assertTrue(context.config.blockedKeywords.contains(replacement))
                assertTrue(!context.config.blockedKeywords.contains(keyword))
                SmokeViews.clickText("Remove $replacement")
                SmokeViews.clickText("Remove")
                assertEquals(original, context.config.blockedKeywords)
                scenario.onActivity { assertShell(it) }
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals("Settings", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
                    assertTrue(activity.findViewById<android.view.View>(R.id.inkpilot_tab_phone).isShown)
                }
                assertEquals(0, legacy.hits)
            }
        } finally {
            instrumentation.removeMonitor(legacy)
            context.config.blockedKeywords = original
            context.baseConfig.lastUnlockTimestampMs = priorUnlock
        }
    }

    private fun setKeyword(context: Context, value: String) {
        assertTrue(keywordNode(context).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun keywordNode(context: Context): AccessibilityNodeInfo =
        SmokeViews.await("keyword editor") {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(context.packageName + ":id/inkpilot_blocked_keyword_input")
                ?.firstOrNull { it.isVisibleToUser }
        }

    private fun assertShell(activity: StudioHomeActivity) {
        assertEquals("Blocked keywords", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
        listOf(R.id.inkpilot_tab_sms, R.id.inkpilot_tab_phone, R.id.inkpilot_tab_contacts,
            R.id.inkpilot_tab_studio, R.id.inkpilot_tab_calendar).forEach { id ->
            assertTrue(activity.findViewById<android.view.View>(id).isShown)
        }
    }
}
