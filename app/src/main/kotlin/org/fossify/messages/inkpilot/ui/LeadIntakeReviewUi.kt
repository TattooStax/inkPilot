package org.fossify.messages.inkpilot.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.fossify.messages.inkpilot.data.LeadIntakeReviewResult
import org.fossify.messages.inkpilot.data.LeadIntakeTarget
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.clearLeadIntake
import org.fossify.messages.inkpilot.data.leadIntakeTargets
import org.fossify.messages.inkpilot.data.reviewLeadIntake
import org.fossify.messages.inkpilot.runtime.AgentRuntime

/** Staff chooses missing fields; the model can never issue or edit this review. */
internal class LeadIntakeReviewUi(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
) {
    private companion object {
        const val TARGET_PREVIEW_LENGTH = 60
        const val CLIENT_CONTEXT_PREVIEW_LENGTH = 72
    }

    fun open() {
        work.load({ repository.leadIntakeTargets() }) { targets ->
            if (targets.isEmpty()) {
                StudioUi.error(activity, "No current client messages to review.")
                return@load
            }
            val labels = targets.map { target ->
                val name = target.displayName.ifBlank { target.recipient }
                val state = when {
                    target.reviewed != null -> "Review saved"
                    target.hasStoredReview -> "Review out of date"
                    else -> "Not reviewed"
                }
                "$name · $state · ${target.inboundText.take(TARGET_PREVIEW_LENGTH)}"
            }.toTypedArray()
            AlertDialog.Builder(activity).setTitle("Review lead questions")
                .setItems(labels) { _, index -> chooseLanguage(targets[index]) }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun chooseLanguage(target: LeadIntakeTarget) {
        val languages = ReplyLanguage.entries
        var choice = target.reviewed?.language ?: ReplyLanguage.EN
        val labels = arrayOf("English", "Español", "Français")
        AlertDialog.Builder(activity).setTitle("Reply language · ${target.displayName}")
            .setSingleChoiceItems(labels, languages.indexOf(choice)) { _, index -> choice = languages[index] }
            .setPositiveButton("Next") { _, _ -> chooseQuestions(target, choice) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseQuestions(target: LeadIntakeTarget, language: ReplyLanguage) {
        val fields = LeadQuestion.entries
        val names = arrayOf("Placement", "Size", "Style", "Reference image", "Preferred artist", "Preferred date")
        val selected = target.reviewed?.missingQuestions?.toMutableSet() ?: mutableSetOf()
        val checked = BooleanArray(fields.size) { fields[it] in selected }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Missing details · ${target.inboundText.take(CLIENT_CONTEXT_PREVIEW_LENGTH)}")
            .setMultiChoiceItems(names, checked) { _, index, isChecked ->
                if (isChecked) selected.add(fields[index]) else selected.remove(fields[index])
            }
            .setPositiveButton("Save review", null)
            .setNegativeButton("Cancel", null)
            .apply {
                if (target.hasStoredReview) setNeutralButton("Clear review") { _, _ ->
                    work.load({ repository.clearLeadIntake(target.request) }) { result -> finish(result) }
                }
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) {
                    StudioUi.error(activity, "Select at least one missing detail, or clear the review.")
                    return@setOnClickListener
                }
                dialog.dismiss()
                work.load({ repository.reviewLeadIntake(target.request, language, selected.toSet()) }) { result ->
                    finish(result)
                }
            }
        }
        dialog.show()
    }

    private fun finish(result: LeadIntakeReviewResult) {
        StudioUi.error(activity, when (result) {
            is LeadIntakeReviewResult.Saved -> if (AgentRuntime.BOUNDED_INTENT_QUALIFIED)
                "Lead questions saved. Generate a held draft for staff review; nothing was sent."
            else "Review saved for a future drafting option. Current drafts do not use it."
            LeadIntakeReviewResult.Stale -> "That conversation changed. Open it and review the latest message."
        })
        refresh()
    }
}
