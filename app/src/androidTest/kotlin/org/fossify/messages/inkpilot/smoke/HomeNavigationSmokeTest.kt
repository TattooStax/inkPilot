package org.fossify.messages.inkpilot.smoke

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeNavigationSmokeTest {
    @Test
    fun dailyTabsRenderWithoutSendingOrCalling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(context.filesDir, "device-test-evidence").apply { mkdirs() }
        ActivityScenario.launch(StudioHomeActivity::class.java).use {
            val tabs = linkedMapOf(
                "SMS" to "New message",
                "Phone" to "Open dialer",
                "Contacts" to "Add contact",
                "Studio" to "Create album",
                "Calendar" to "Add appointment or task",
            )
            tabs.forEach { (tab, anchor) ->
                SmokeViews.clickDescription(tab)
                SmokeViews.assertText(anchor)
                when (tab) {
                    "Contacts" -> SmokeViews.assertText("+12025550123")
                    "Studio" -> {
                        SmokeViews.assertText("Finished Work")
                        SmokeViews.assertText("Outlines")
                        SmokeViews.assertText("Client References")
                    }
                    "Calendar" -> SmokeViews.assertText("Nothing scheduled for this day.")
                }
                SmokeViews.capture(File(evidence, "tab-${tab.lowercase()}.png"))
            }
        }
    }
}
