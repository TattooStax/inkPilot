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

/** Staff chooses missing fields; the model can never issue or edit this review. */
internal class LeadIntakeReviewUi(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
) {
    fun open() {
        work.load({ repository.leadIntakeTargets() }) { targets ->
            if (targets.isEmpty()) {
                StudioUi.error(activity, "No current client messages to review.")
                return@load
            }
            val labels = targets.map { target ->
                val name = target.displayName.ifBlank { target.recipient }
                val state = if (target.reviewed == null) "Not reviewed" else "Review saved"
                "$name · $state · ${target.inboundText.take(60)}"
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
            .setTitle("Which details are still missing?")
            .setMessage("Client: ${target.inboundText.take(240)}\nOnly select details staff checked. No reply is sent.")
            .setMultiChoiceItems(names, checked) { _, index, isChecked ->
                if (isChecked) selected.add(fields[index]) else selected.remove(fields[index])
            }
            .setPositiveButton("Save review", null)
            .setNegativeButton("Cancel", null)
            .apply {
                if (target.reviewed != null) setNeutralButton("Clear review") { _, _ ->
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
                work.load({ repository.reviewLeadIntake(target.request, language, selected.toSet()) }) { result -> finish(result) }
            }
        }
        dialog.show()
    }

    private fun finish(result: LeadIntakeReviewResult) {
        StudioUi.error(activity, when (result) {
            is LeadIntakeReviewResult.Saved -> "Lead questions saved for staff review. Nothing was sent."
            LeadIntakeReviewResult.Stale -> "That conversation changed. Open it and review the latest message."
        })
        refresh()
    }
}
