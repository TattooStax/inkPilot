package org.fossify.messages.inkpilot.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                activity.setContentView(DraftPanel(activity, repository,
                    StudioWork(activity, Executor { it.run() }), refresh = {}).view())
                assertFalse(hasText(activity.window.decorView, "Review lead questions"))
            }
            openStagedDialog(scenario)
            SmokeViews.clickText("Maya · Not reviewed · Can I book a consultation?")
            SmokeViews.clickText("Next")
            SmokeViews.clickText("Size")
            SmokeViews.clickText("Save review")
            SmokeViews.await("saved lead question") {
                repository.leadIntakeTargets().single().reviewed
                    ?.takeIf { it.missingQuestions == setOf(LeadQuestion.SIZE) }
            }
            val firstRevision = repository.leadIntakeTargets().single().request.expectedRevision

            openStagedDialog(scenario)
            SmokeViews.clickText("Maya · Review saved · Can I book a consultation?")
            SmokeViews.clickText("Next")
            SmokeViews.clickText("Size")
            SmokeViews.clickText("Placement")
            SmokeViews.clickText("Save review")
            SmokeViews.await("corrected lead question") {
                repository.leadIntakeTargets().single().reviewed
                    ?.takeIf { it.missingQuestions == setOf(LeadQuestion.PLACEMENT) }
            }
            assertEquals(firstRevision + 1, repository.leadIntakeTargets().single().request.expectedRevision)

            openStagedDialog(scenario)
            SmokeViews.clickText("Maya · Review saved · Can I book a consultation?")
            SmokeViews.clickText("Next")
            SmokeViews.clickText("Clear review")
            SmokeViews.await("cleared lead question") {
                repository.leadIntakeTargets().single().takeIf { it.reviewed == null }
            }
            assertNull(repository.leadIntakeTargets().single().reviewed)
        }
    }

    private fun openStagedDialog(scenario: ActivityScenario<StudioHomeActivity>) {
        scenario.onActivity { activity ->
            LeadIntakeReviewUi(activity, repository, StudioWork(activity, Executor { it.run() }), refresh = {}).open()
        }
    }

    private fun hasText(view: View, text: String): Boolean = when (view) {
        is TextView -> view.text.toString() == text
        is ViewGroup -> (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
        else -> false
    }
}
