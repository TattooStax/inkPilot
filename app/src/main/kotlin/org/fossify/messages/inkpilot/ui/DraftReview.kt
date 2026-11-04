package org.fossify.messages.inkpilot.ui

import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.inkpilot.data.ReviewDraft

/** Compatibility wrapper renders review inside the current shell. */
internal class DraftReview(private val activity: AppCompatActivity, private val work: StudioWork,
    private val refresh: () -> Unit) {
    fun show(draft: ReviewDraft) {
        (activity as? StudioHomeActivity)?.openRoute(StudioRoute.Draft(draft.draftId))
    }
}
