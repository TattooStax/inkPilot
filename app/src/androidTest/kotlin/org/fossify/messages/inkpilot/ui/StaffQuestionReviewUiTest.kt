package org.fossify.messages.inkpilot.ui

import android.widget.Button
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.data.LeadIntakeReviewResult
import org.fossify.messages.inkpilot.data.StaffQuestionResult
import org.fossify.messages.inkpilot.data.StudioDatabaseTestBase
import org.fossify.messages.inkpilot.data.holdStaffQuestion
import org.fossify.messages.inkpilot.data.reviewLeadIntake
import org.fossify.messages.inkpilot.data.saveStaffEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class StaffQuestionReviewUiTest : StudioDatabaseTestBase() {
    @Test fun heldStaffQuestionCanBeEditedAndReviewedWithModelGateOff() {
        repository.setDraftingEnabled(false)
        val source = inbound()
        val reviewed = (repository.reviewLeadIntake(source, ReplyLanguage.EN,
            setOf(LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved).request
        val id = (repository.holdStaffQuestion(reviewed, LeadQuestion.SIZE) as StaffQuestionResult.Held).draftId
        val staff = requireNotNull(repository.draft(id))
        assertTrue(reviewableInUi(staff))
        val old = staff.copy(rendererPolicy = inkpilot.agent.DraftRendererPolicy.LEGACY_RAW)
        assertFalse(reviewableInUi(old))
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.appLockManager.unlock()
                val view = InlineDraftReview(activity, StudioWork(activity, Executor { it.run() }),
                    refresh = {}, saveEdit = repository::saveStaffEdit).view(staff)
                activity.setContentView(view)
                val approve = requireNotNull(find(view, Button::class.java) { it.text == "Approve & send" })
                assertTrue(approve.isEnabled)
                assertTrue(requireNotNull(find(view, Button::class.java) { it.text == "Choose business SIM" }).isEnabled)
                val editor = requireNotNull(find(view, EditText::class.java) { it.hint == "Edit held reply" })
                editor.setText("What size do you prefer?")
                assertEquals("What size do you prefer?", repository.draft(id)?.staffEditBody)
                assertTrue(approve.isEnabled)
                assertEquals(null, repository.getSendClaim(id)) // This UI test never touches a SIM.
            }
        }
    }

    private fun <T : View> find(root: View, type: Class<T>, matches: (T) -> Boolean): T? {
        if (type.isInstance(root) && matches(type.cast(root)!!)) return type.cast(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            find(root.getChildAt(index), type, matches)?.let { return it }
        }
        return null
    }
}
