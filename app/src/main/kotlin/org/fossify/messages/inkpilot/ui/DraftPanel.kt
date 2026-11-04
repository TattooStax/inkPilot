package org.fossify.messages.inkpilot.ui

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.R
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.inkpilot.data.StudioRepository

internal class DraftPanel(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
) {
    fun view(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Drafts to review", 24f, true))
        body.addView(StudioUi.text(activity, "Nothing sends until a staff member reviews and approves it.", 14f))
        body.addView(StudioUi.button(activity, "Refresh", refresh))
        val list = StudioUi.column(activity, 0).apply { id = R.id.inkpilot_draft_list }
        body.addView(list)
        list.addView(StudioUi.text(activity, "Loading drafts…"))
        work.load({ repository.drafts() }) { drafts ->
            list.removeAllViews()
            if (drafts.isEmpty()) list.addView(StudioUi.text(activity, "No drafts yet. Set up a local model to start."))
            drafts.forEach { draft ->
                list.addView(StudioUi.text(activity, draft.displayName.ifBlank { draft.recipient }, 18f, true))
                list.addView(StudioUi.text(activity, draft.recipient, 13f))
                list.addView(StudioUi.text(activity, "Client: ${draft.inboundText}", 14f).apply { maxLines = 4 })
                list.addView(StudioUi.text(activity, draft.sendBody ?: draft.body, 16f))
                list.addView(StudioUi.text(activity, statusText(draft.status, draft.canReview), 13f))
                if (draft.status != "HELD" || !draft.canReview) {
                    list.addView(StudioUi.button(activity, "View conversation") {
                        activity.startActivity(Intent(activity, ThreadActivity::class.java)
                            .putExtra(THREAD_ID, draft.threadId).putExtra(THREAD_TITLE, draft.displayName))
                    })
                }
                if (draft.status == "HELD") {
                    if (!draft.canReview) list.addView(StudioUi.button(activity, "Regenerate with current memory") {
                        work.load({
                            val source = repository.readSource(draft.eventId)
                            source?.let { org.fossify.messages.inkpilot.runtime.AgentRuntime.regenerate(activity, it.eventId, it.revision) }
                        }) { refresh() }
                    })
                    if (draft.canReview) list.addView(StudioUi.button(activity, "Review draft") {
                        DraftReview(activity, work, refresh).show(draft)
                    }.apply { contentDescription = "Review draft" })
                    list.addView(StudioUi.button(activity, "Dismiss") {
                        work.load({ repository.dismissDraft(draft.draftId) }) { refresh() }
                    })
                }
                list.addView(StudioUi.rule(activity))
            }
        }
        return StudioUi.scroll(activity, body)
    }

    private fun statusText(status: String, current: Boolean) = when (status) {
        "HELD" -> if (current) "Ready for staff review" else "Out of date or paused — dismiss and review the conversation."
        "SENDING" -> "Sending — awaiting the phone’s result"
        "SENT" -> "Sent to the carrier"
        "FAILED" -> "Send failed — check the conversation before trying again."
        "UNKNOWN" -> "Send outcome unknown — check the conversation before sending again."
        else -> "Review the conversation for the latest status."
    }
}
