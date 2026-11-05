package org.fossify.messages.inkpilot.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import inkpilot.agent.LeadQuestion
import org.fossify.messages.inkpilot.data.StaffQuestionResult
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.holdStaffQuestion
import org.fossify.messages.inkpilot.data.staffQuestionTarget

/** Staff selects a reviewed missing field; creation only saves a held, editable draft. */
internal class StaffQuestionUi(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
    private val navigate: (StudioRoute) -> Unit,
) {
    fun open(threadId: Long) {
        work.load({ repository.staffQuestionTarget(threadId) }) { target ->
            when {
                target == null -> StudioUi.error(activity, "No current client message is available in this conversation.")
                target.reviewed == null -> AlertDialog.Builder(activity)
                    .setTitle("Review the missing details")
                    .setMessage("Read the latest client message, choose a language and mark what is still missing. Nothing will send.")
                    .setPositiveButton("Review details") { _, _ ->
                        LeadIntakeReviewUi(activity, repository, work, refresh).openForThread(threadId)
                    }.setNegativeButton("Cancel", null).show()
                else -> {
                    val questions = target.reviewed.missingQuestions.sortedBy { it.ordinal }
                    AlertDialog.Builder(activity).setTitle("Ask about one missing detail")
                        .setItems(questions.map(::label).toTypedArray()) { _, index ->
                            work.load({ repository.holdStaffQuestion(target.request, questions[index]) }) { result ->
                                when (result) {
                                    is StaffQuestionResult.Held -> navigate(StudioRoute.Draft(result.draftId))
                                    StaffQuestionResult.Stale -> {
                                        StudioUi.error(activity, "The conversation or review changed. Review the latest message.")
                                        refresh()
                                    }
                                }
                            }
                        }
                        .setNeutralButton("Edit missing details") { _, _ ->
                            LeadIntakeReviewUi(activity, repository, work, refresh).openForThread(threadId)
                        }.setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    private fun label(question: LeadQuestion): String = when (question) {
        LeadQuestion.PLACEMENT -> "Placement"
        LeadQuestion.SIZE -> "Size"
        LeadQuestion.STYLE -> "Style"
        LeadQuestion.REFERENCE -> "Reference image"
        LeadQuestion.ARTIST -> "Preferred artist"
        LeadQuestion.DATE -> "Preferred date"
    }
}
