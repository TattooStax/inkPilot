package org.fossify.messages.inkpilot.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.R
import org.fossify.messages.extensions.config

/** The inherited keyword filter remains editable without leaving the InkPilot shell. */
internal class BlockedKeywordsPanel(private val activity: StudioHomeActivity, private val refresh: () -> Unit,
                                    private val editState: BlockedKeywordEditState) {
    fun view(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Blocked keywords", 22f, true))
        body.addView(StudioUi.text(activity,
            "Matching incoming SMS are held in Agent review instead of the normal inbox. " +
                "Review the filtered arrivals before dismissing them.", 14f))
        body.addView(StudioUi.button(activity, "Add keyword") { edit(null) })
        val keywords = activity.config.blockedKeywords.sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (keywords.isEmpty()) body.addView(StudioUi.text(activity, "No blocked keywords."))
        keywords.forEach { keyword ->
            body.addView(StudioUi.text(activity, keyword, 17f, true))
            body.addView(StudioUi.button(activity, "Edit $keyword") { edit(keyword) })
            body.addView(StudioUi.button(activity, "Remove $keyword") { remove(keyword) })
        }
        return StudioUi.scroll(activity, body).also { surface ->
            editState.takeRestoration()?.let { (original, draft) ->
                surface.post {
                    if (activity.currentRoute == StudioRoute.Auxiliary("blocked_keywords") &&
                        !activity.isFinishing && !activity.isDestroyed && !activity.appLockManager.isLocked()) {
                        edit(original, draft)
                    } else editState.deferRestoration()
                }
            }
        }
    }

    private fun edit(original: String?, draft: String = original.orEmpty()) {
        if (activity.appLockManager.isLocked()) return
        editState.begin(original, draft)
        val input = EditText(activity).apply {
            id = R.id.inkpilot_blocked_keyword_input
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(draft)
            selectAll()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    editState.update(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val dialog = AlertDialog.Builder(activity).setTitle(if (original == null) "Add keyword" else "Edit keyword")
            .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { editState.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (activity.appLockManager.isLocked()) { dialog.dismiss(); return@setOnClickListener }
                val value = input.text.toString().trim()
                if (value.isEmpty()) { input.error = "Enter a keyword"; return@setOnClickListener }
                val current = activity.config.blockedKeywords
                activity.config.blockedKeywords = (if (original == null) current else current - original) + value
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
        editState.attach(dialog)
    }

    private fun remove(keyword: String) {
        if (activity.appLockManager.isLocked()) return
        AlertDialog.Builder(activity).setTitle("Remove blocked keyword?")
            .setMessage(keyword).setPositiveButton("Remove") { _, _ ->
                if (activity.appLockManager.isLocked()) return@setPositiveButton
                activity.config.removeBlockedKeyword(keyword)
                refresh()
            }.setNegativeButton("Keep", null).show()
    }
}
