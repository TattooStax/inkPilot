package org.fossify.messages.inkpilot.ui

import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.fossify.messages.inkpilot.data.ReviewDraft

internal const val MAX_STAFF_REPLY_LENGTH = 800

/** Approval remains disabled until the last edit has a durable Studio row. */
internal fun watchStaffEdit(editor: EditText, approve: Button, status: TextView, draft: ReviewDraft,
                            work: StudioWork, save: (String, String) -> Boolean) {
    if (!reviewableInUi(draft)) return
    var generation = 0
    editor.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (!draft.canReview || draft.status != "HELD") return
            val current = ++generation
            val body = s.toString()
            approve.isEnabled = false
            status.text = "Saving edit on this device…"
            work.load({ save(draft.draftId, body) }, failed = {
                if (current == generation) status.text = "Edit could not be saved. Copy it before leaving."
            }) { saved ->
                if (current == generation) {
                    approve.isEnabled = saved && body.isNotBlank()
                    status.text = if (saved) "Saved locally. Nothing sends until staff approves."
                        else "Draft changed. Review the conversation again."
                }
            }
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
}
