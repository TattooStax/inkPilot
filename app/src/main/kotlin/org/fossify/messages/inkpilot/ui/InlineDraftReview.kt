package org.fossify.messages.inkpilot.ui

import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.inkpilot.data.ReviewDraft
import org.fossify.messages.inkpilot.runtime.AgentRuntime

private const val CLIENT_CONTEXT_LINES = 2
private const val CLIENT_CONTEXT_SP = 14f

/** Staff approval lives in the thread; the model never calls a send API. */
internal class InlineDraftReview(private val activity: AppCompatActivity, private val work: StudioWork,
    private val refresh: () -> Unit) {
    fun view(draft: ReviewDraft): android.view.View {
        val root = StudioUi.column(activity, 8)
        root.addView(StudioUi.text(activity, "Held assistant reply · ${draft.recipient}", 16f, true))
        root.addView(StudioUi.text(activity, "Client: ${draft.inboundText}", CLIENT_CONTEXT_SP).apply {
            maxLines = CLIENT_CONTEXT_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val prefs = activity.getSharedPreferences("held_staff_edits", 0)
        val editor = EditText(activity).apply {
            id = android.view.View.generateViewId()
            hint = "Edit held reply"
            setText(prefs.getString(draft.draftId, draft.body))
            minLines = 2
            maxLines = 5
        }
        root.addView(StudioUi.text(activity, "Reply to review", 14f).apply { labelFor = editor.id })
        root.addView(editor)
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { prefs.edit().putString(draft.draftId, s.toString()).commit() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        var sim = -1
        val select = StudioUi.button(activity, "Choose business SIM") {}
        select.setOnClickListener { chooseComposerSim(activity, work) { id, label -> sim = id; select.text = label } }
        root.addView(select)
        val status = StudioUi.text(activity, if (draft.canReview) "Nothing sends until staff approves." else "Out of date or paused. Review current memory before regenerating.")
        root.addView(status)
        val approve = StudioUi.button(activity, "Approve & send") {}
        approve.isEnabled = draft.canReview && draft.status == "HELD"
        approve.setOnClickListener {
            if (sim < 0 || editor.text.isBlank()) { status.text = "Write a reply and explicitly choose the business SIM"; return@setOnClickListener }
            val approvedBody = editor.text.toString()
            val approvedSim = sim
            approve.isEnabled = false
            work.load({ AgentRuntime.approveAndSend(activity.applicationContext, draft.draftId, approvedBody, approvedSim) }) { status.text = it; refresh() }
        }
        root.addView(approve)
        return root
    }
}

/** The selected review remains first even when a newer held draft shares the conversation. */
internal fun prioritizeThreadDrafts(drafts: List<ReviewDraft>, selectedDraftId: String?): List<ReviewDraft> =
    if (selectedDraftId == null) drafts else drafts.sortedByDescending { it.draftId == selectedDraftId }
