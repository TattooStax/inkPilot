package org.fossify.messages.inkpilot.ui

import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.inkpilot.data.ReviewDraft
import org.fossify.messages.inkpilot.runtime.AgentRuntime

private const val CLIENT_CONTEXT_LINES = 2
private const val CLIENT_CONTEXT_SP = 14f
private const val DRAFT_HEADING_SP = 16f

private fun reviewHeading(): String = if (AgentRuntime.BOUNDED_INTENT_QUALIFIED)
    "Held assistant reply" else "Earlier unqualified model text"

private fun reviewLabel(): String = if (AgentRuntime.BOUNDED_INTENT_QUALIFIED)
    "Reply to review" else "Historical text for staff context"

/** Staff approval lives in the thread; the model never calls a send API. */
internal class InlineDraftReview(private val activity: AppCompatActivity, private val work: StudioWork,
    private val refresh: () -> Unit, private val saveEdit: (String, String) -> Boolean) {
    fun view(draft: ReviewDraft): android.view.View {
        val canReview = draft.canReview && AgentRuntime.BOUNDED_INTENT_QUALIFIED
        val root = StudioUi.column(activity, 8)
        root.addView(StudioUi.text(activity, "${reviewHeading()} · ${draft.recipient}", DRAFT_HEADING_SP, true))
        root.addView(StudioUi.text(activity, "Client: ${draft.inboundText}", CLIENT_CONTEXT_SP).apply {
            maxLines = CLIENT_CONTEXT_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val editor = EditText(activity).apply {
            id = android.view.View.generateViewId()
            hint = "Edit held reply"
            setText(draft.sendBody ?: draft.staffEditBody ?: draft.body)
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_STAFF_REPLY_LENGTH))
            isEnabled = canReview && draft.status == "HELD"
            minLines = 2
            maxLines = 5
        }
        root.addView(StudioUi.text(activity, reviewLabel(), CLIENT_CONTEXT_SP).apply { labelFor = editor.id })
        root.addView(editor)
        var sim = -1
        val select = StudioUi.button(activity, "Choose business SIM") {}
        select.isEnabled = canReview
        select.setOnClickListener { chooseComposerSim(activity, work) { id, label -> sim = id; select.text = label } }
        root.addView(select)
        val status = StudioUi.text(activity, if (canReview) "Nothing sends until staff approves." else
            "Model draft approval is unavailable. Reply directly in the conversation.")
        root.addView(status)
        val approve = StudioUi.button(activity, "Approve & send") {}
        approve.isEnabled = canReview && draft.status == "HELD"
        approve.setOnClickListener {
            if (sim < 0 || editor.text.isBlank()) { status.text = "Write a reply and explicitly choose the business SIM"; return@setOnClickListener }
            val approvedBody = editor.text.toString()
            val approvedSim = sim
            approve.isEnabled = false
            work.load({ AgentRuntime.approveAndSend(activity.applicationContext, draft.draftId, approvedBody, approvedSim) }) { status.text = it; refresh() }
        }
        root.addView(approve)
        watchStaffEdit(editor, approve, status, draft, work, saveEdit)
        return root
    }
}

/** The selected review remains first even when a newer held draft shares the conversation. */
internal fun prioritizeThreadDrafts(drafts: List<ReviewDraft>, selectedDraftId: String?): List<ReviewDraft> =
    if (selectedDraftId == null) drafts else drafts.sortedByDescending { it.draftId == selectedDraftId }
