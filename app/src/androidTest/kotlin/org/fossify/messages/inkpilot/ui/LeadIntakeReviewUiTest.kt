package org.fossify.messages.inkpilot.ui

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.LeadQuestion
import org.fossify.messages.inkpilot.data.StudioDatabaseTestBase
import org.fossify.messages.inkpilot.data.leadIntakeTargets
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class LeadIntakeReviewUiTest : StudioDatabaseTestBase() {
    @Test fun stagedDialogCanSaveCorrectAndClearMissingQuestionsWithoutProductionEntry() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        inbound()
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = DraftPanel(activity, repository,
                    StudioWork(activity, Executor { it.run() }), refresh = {}).view()
                assertFalse(hasText(screen, "Review lead questions"))
                val fixture = Button(activity).apply {
                    text = "Open staged review fixture"
                    setOnClickListener {
                        LeadIntakeReviewUi(activity, repository,
                            StudioWork(activity, Executor { it.run() }), refresh = {}).open()
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(screen)
                    addView(fixture, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                })
            }
            openStagedDialog()
            clickTarget("Maya · Not reviewed · Can I book a consultation?")
            SmokeViews.clickText("Next")
            SmokeViews.clickText("Size")
            SmokeViews.clickText("Save review")
            SmokeViews.await("saved lead question") {
                repository.leadIntakeTargets().single().reviewed
                    ?.takeIf { it.missingQuestions == setOf(LeadQuestion.SIZE) }
            }
            val firstRevision = repository.leadIntakeTargets().single().request.expectedRevision

            openStagedDialog()
            clickTarget("Maya · Review saved · Can I book a consultation?")
            SmokeViews.clickText("Next")
            SmokeViews.clickText("Size")
            SmokeViews.clickText("Placement")
            SmokeViews.clickText("Save review")
            SmokeViews.await("corrected lead question") {
                repository.leadIntakeTargets().single().reviewed
                    ?.takeIf { it.missingQuestions == setOf(LeadQuestion.PLACEMENT) }
            }
            assertEquals(firstRevision + 1, repository.leadIntakeTargets().single().request.expectedRevision)

            openStagedDialog()
            clickTarget("Maya · Review saved · Can I book a consultation?")
            SmokeViews.clickText("Next")
            SmokeViews.clickText("Clear review")
            SmokeViews.await("cleared lead question") {
                repository.leadIntakeTargets().single().takeIf { it.reviewed == null }
            }
            assertNull(repository.leadIntakeTargets().single().reviewed)
        }
    }

    private fun openStagedDialog() = SmokeViews.clickText("Open staged review fixture")

    private fun clickTarget(label: String) {
        try {
            SmokeViews.clickText(label)
        } catch (failure: AssertionError) {
            val targets = repository.leadIntakeTargets().map {
                "${it.displayName}:${it.reviewed != null}:${it.hasStoredReview}"
            }
            throw AssertionError("Staged target unavailable. Sources=$targets; active=${activeLabels()}", failure)
        }
    }

    private fun activeLabels(): List<String> = buildList {
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || size >= 60) return
            node.text?.toString()?.take(100)?.let { add(it) }
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun hasText(view: View, text: String): Boolean = when (view) {
        is TextView -> view.text.toString() == text
        is ViewGroup -> (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
        else -> false
    }
}
