package org.fossify.messages.inkpilot.ui

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.ReviewDraft
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.StudioStaffAttention
import org.fossify.messages.inkpilot.data.markStaffAttentionHandled
import org.fossify.messages.inkpilot.data.staffAttention

internal class DraftPanel(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
    private val open: (StudioRoute) -> Unit = {},
) {
    fun view(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Drafts and staff replies", TITLE_SIZE, true))
        body.addView(StudioUi.text(activity, "Nothing sends until a staff member reviews and approves it.", NOTE_SIZE))
        body.addView(StudioUi.button(activity, "Review lead questions") {
            LeadIntakeReviewUi(activity, repository, work, refresh).open()
        })
        body.addView(StudioUi.button(activity, "Refresh", refresh))
        val list = StudioUi.column(activity, 0).apply { id = R.id.inkpilot_draft_list }
        body.addView(list)
        list.addView(StudioUi.text(activity, "Loading staff replies and drafts…"))
        work.load({ repository.staffAttention() to repository.drafts() }) { (attention, drafts) ->
            list.removeAllViews()
            renderAttention(list, attention)
            if (attention.isEmpty() && drafts.isEmpty()) {
                list.addView(StudioUi.text(activity, "No replies or drafts need review."))
            }
            renderDrafts(list, drafts)
        }
        return StudioUi.scroll(activity, body)
    }

    private fun renderAttention(list: LinearLayout, attention: List<StudioStaffAttention>) {
        if (attention.isEmpty()) return
        list.addView(StudioUi.text(activity, "Staff reply needed", SECTION_SIZE, true))
        attention.forEach { task ->
            list.addView(StudioUi.text(activity, task.displayName.ifBlank { task.recipient }, NAME_SIZE, true))
            list.addView(StudioUi.text(activity, task.recipient, DETAIL_SIZE))
            list.addView(StudioUi.text(activity, "Client: ${task.inboundText}", NOTE_SIZE).apply {
                maxLines = MESSAGE_LINES
            })
            list.addView(StudioUi.text(activity, attentionText(task), NOTE_SIZE))
            if (!task.current) {
                list.addView(StudioUi.text(activity, "Context changed. Read the latest conversation.", DETAIL_SIZE))
            }
            list.addView(StudioUi.button(activity, "Open conversation") { open(StudioRoute.Thread(task.threadId)) })
            list.addView(StudioUi.text(activity, "After replying or taking over, mark handled.", DETAIL_SIZE))
            list.addView(StudioUi.button(activity, "Mark handled") {
                work.load({ repository.markStaffAttentionHandled(task.eventId, task.sourceRevision) }) { refresh() }
            })
            list.addView(StudioUi.rule(activity))
        }
    }

    private fun attentionText(task: StudioStaffAttention) = when (task.reason) {
        "UNSAFE_CONTENT" -> "Local draft blocked an unverified booking claim. Staff should reply directly."
        "OTHER_THREAD_RECEIVED" -> "Another conversation for this client became active. Review this thread."
        "IDENTITY_REASSIGNED" -> "This number changed clients. Verify this conversation before replying."
        "Model or source read failed" -> "Local draft failed. Staff should reply directly and check model setup."
        else -> "No safe local draft was saved. Staff should reply directly."
    }

    private fun renderDrafts(list: LinearLayout, drafts: List<ReviewDraft>) {
        if (drafts.isNotEmpty()) list.addView(StudioUi.text(activity, "Drafts to review", SECTION_SIZE, true))
        drafts.forEach { renderDraft(list, it) }
    }

    private fun renderDraft(list: LinearLayout, draft: ReviewDraft) {
        list.addView(StudioUi.text(activity, draft.displayName.ifBlank { draft.recipient }, NAME_SIZE, true))
        list.addView(StudioUi.text(activity, draft.recipient, DETAIL_SIZE))
        list.addView(StudioUi.text(activity, "Client: ${draft.inboundText}", NOTE_SIZE).apply {
            maxLines = MESSAGE_LINES
        })
        list.addView(StudioUi.text(activity, draft.sendBody ?: draft.body, DRAFT_SIZE))
        list.addView(StudioUi.text(activity, statusText(draft.status, draft.canReview), DETAIL_SIZE))
        if (draft.status != "HELD" || !draft.canReview) {
            list.addView(StudioUi.button(activity, "View conversation") {
                open(StudioRoute.Thread(draft.threadId))
            })
        }
        if (draft.status == "HELD") {
            if (!draft.canReview) list.addView(StudioUi.button(activity, "Regenerate with current memory") {
                work.load({
                    val source = repository.readSource(draft.eventId)
                    source?.let {
                        org.fossify.messages.inkpilot.runtime.AgentRuntime.regenerate(activity, it.eventId, it.revision)
                    }
                }) { refresh() }
            })
            if (draft.canReview) list.addView(StudioUi.button(activity, "Review draft") {
                open(StudioRoute.Draft(draft.draftId))
            }.apply { contentDescription = "Review draft" })
            list.addView(StudioUi.button(activity, "Dismiss") {
                work.load({ repository.dismissDraft(draft.draftId) }) { refresh() }
            })
        }
        list.addView(StudioUi.rule(activity))
    }

    private fun statusText(status: String, current: Boolean) = when (status) {
        "HELD" -> if (current) "Ready for staff review" else
            "Out of date or paused — dismiss and review the conversation."
        "SENDING" -> "Sending — awaiting the phone’s result"
        "SENT" -> "Sent to the carrier"
        "FAILED" -> "Send failed — check the conversation before trying again."
        "UNKNOWN" -> "Send outcome unknown — check the conversation before sending again."
        else -> "Review the conversation for the latest status."
    }

    private companion object {
        const val TITLE_SIZE = 24f
        const val SECTION_SIZE = 20f
        const val NAME_SIZE = 18f
        const val DRAFT_SIZE = 16f
        const val NOTE_SIZE = 14f
        const val DETAIL_SIZE = 13f
        const val MESSAGE_LINES = 4
    }
}
