package org.fossify.messages.inkpilot.ui

import android.os.Build
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.LeadQuestion
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.inkpilot.data.StudioDatabaseTestBase
import org.fossify.messages.inkpilot.data.leadIntakeTargets
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class LeadIntakeReviewUiTest : StudioDatabaseTestBase() {
    @Test fun stagedDialogCanSaveCorrectAndClearMissingQuestionsWithoutProductionEntry() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlockTimestamp = context.baseConfig.lastUnlockTimestampMs
        context.appLockManager.unlock()
        try {
            inbound()
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val screen = DraftPanel(activity, repository,
                        StudioWork(activity, Executor { it.run() }), refresh = {}).view()
                    assertFalse(hasText(screen, "Review lead questions"))
                    activity.setContentView(screen)
                }
                openStagedDialog(scenario)
                clickTarget("Maya · Not reviewed · Can I book a consultation?")
                SmokeViews.clickText("Next")
                clickTarget("Size")
                SmokeViews.clickText("Save review")
                SmokeViews.await("saved lead question") {
                    repository.leadIntakeTargets().single().reviewed
                        ?.takeIf { it.missingQuestions == setOf(LeadQuestion.SIZE) }
                }
                val firstRevision = repository.leadIntakeTargets().single().request.expectedRevision

                openStagedDialog(scenario)
                clickTarget("Maya · Review saved · Can I book a consultation?")
                SmokeViews.clickText("Next")
                clickTarget("Size")
                clickTarget("Placement")
                SmokeViews.clickText("Save review")
                SmokeViews.await("corrected lead question") {
                    repository.leadIntakeTargets().single().reviewed
                        ?.takeIf { it.missingQuestions == setOf(LeadQuestion.PLACEMENT) }
                }
                assertEquals(firstRevision + 1, repository.leadIntakeTargets().single().request.expectedRevision)

                openStagedDialog(scenario)
                clickTarget("Maya · Review saved · Can I book a consultation?")
                SmokeViews.clickText("Next")
                SmokeViews.clickText("Clear review")
                SmokeViews.await("cleared lead question") {
                    repository.leadIntakeTargets().single().takeIf { it.reviewed == null }
                }
                assertNull(repository.leadIntakeTargets().single().reviewed)
            }
        } finally {
            context.baseConfig.lastUnlockTimestampMs = priorUnlockTimestamp
        }
    }

    private fun openStagedDialog(scenario: ActivityScenario<StudioHomeActivity>) {
        scenario.onActivity { activity ->
            assertFalse("Studio UI must be unlocked for review", activity.appLockManager.isLocked())
            val fixture = Button(activity).apply {
                setOnClickListener {
                    LeadIntakeReviewUi(activity, repository,
                        StudioWork(activity, Executor { it.run() }), refresh = {}).open()
                }
            }
            assertTrue("The staged review trigger must run", fixture.performClick())
        }
    }

    private fun clickTarget(label: String) {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val node = SmokeViews.await("visible dialog choice: $label") {
                findVisibleText(instrumentation.uiAutomation.rootInActiveWindow, label)
            }
            // AlertDialog's ListView handles the row touch, but its visible text node
            // does not expose ACTION_CLICK (nor does any accessibility ancestor).
            val bounds = Rect().also(node::getBoundsInScreen)
            assertFalse("Dialog choice has empty bounds: $label", bounds.isEmpty)
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 20, MotionEvent.ACTION_UP, x, y, 0)
            try {
                assertTrue("Dialog choice touch down rejected: $label", instrumentation.uiAutomation.injectInputEvent(down, true))
                assertTrue("Dialog choice touch up rejected: $label", instrumentation.uiAutomation.injectInputEvent(up, true))
            } finally {
                down.recycle()
                up.recycle()
            }
            instrumentation.waitForIdleSync()
        } catch (failure: AssertionError) {
            val targets = repository.leadIntakeTargets().map {
                "${it.displayName}:${it.reviewed != null}:${it.hasStoredReview}"
            }
            throw AssertionError("Staged target unavailable. Sources=$targets; active=${activeLabels()}", failure)
        }
    }

    private fun findVisibleText(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findVisibleText(node.getChild(index), label)?.let { return it }
        }
        return null
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
