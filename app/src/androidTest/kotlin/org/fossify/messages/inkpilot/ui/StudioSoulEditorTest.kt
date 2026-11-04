package org.fossify.messages.inkpilot.ui

import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.R
import org.fossify.messages.inkpilot.runtime.AgentSettings
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioSoulEditorTest {
    @Test
    fun unsavedGuidanceSurvivesResumeAndRecreationButRequiresExplicitSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AgentSettings(context)
        val saved = settings.soul
        val draft = "Synthetic staff guidance: ask which artist the client prefers."
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                SmokeViews.clickDescription("Open agent controls")
                SmokeViews.clickText("Soul & studio memory")
                awaitEditor(scenario, saved)
                scenario.onActivity { it.findViewById<EditText>(R.id.inkpilot_soul).setText(draft) }
                assertEquals("Typing must not change canonical memory", saved, settings.soul)

                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitEditor(scenario, draft)
                assertEquals("Returning from another app must not save implicitly", saved, settings.soul)

                scenario.recreate()
                awaitEditor(scenario, draft)
                assertEquals("Restoring UI state must not update canonical memory", saved, settings.soul)

                // An intentionally cleared draft must not reload old saved guidance either.
                scenario.onActivity { it.findViewById<EditText>(R.id.inkpilot_soul).setText("") }
                scenario.recreate()
                awaitEditor(scenario, "")
                assertEquals(saved, settings.soul)
                scenario.onActivity { it.findViewById<EditText>(R.id.inkpilot_soul).setText(draft) }
                SmokeViews.clickText("Save studio memory")
                SmokeViews.await("explicit studio-memory save") { if (settings.soul == draft) true else null }
            }
        } finally {
            settings.soul = saved
        }
    }

    private fun awaitEditor(scenario: ActivityScenario<StudioHomeActivity>, text: String) {
        SmokeViews.await("restored editable studio memory") {
            var ready = false
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.inkpilot_soul)
                ready = editor != null && editor.isEnabled && editor.text.toString() == text
            }
            if (ready) true else null
        }
    }
}
