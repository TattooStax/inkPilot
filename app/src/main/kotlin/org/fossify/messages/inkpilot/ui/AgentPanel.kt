package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.content.Intent
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.fossify.messages.R
import org.fossify.messages.inkpilot.runtime.AgentRuntime
import org.fossify.messages.inkpilot.runtime.AgentSettings

internal class AgentPanel(
    private val activity: AppCompatActivity,
    private val work: StudioWork,
    private val refresh: () -> Unit,
    initialSoulDraft: String? = null,
) {
    // Uncommitted guidance is UI state; only the explicit Save action writes studio memory.
    var soulDraft: String? = initialSoulDraft
        private set

    fun model(): View {
        val body = StudioUi.column(activity)
        val settings = AgentSettings(activity.applicationContext)
        body.addView(StudioUi.text(activity, "Local model", 24f, true))
        body.addView(StudioUi.text(activity, settings.modelName ?: "No model selected", 17f, true))
        body.addView(StudioUi.text(activity, settings.status, 14f).apply { id = R.id.inkpilot_model_status })
        body.addView(StudioUi.button(activity, "Choose model file") {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, MODEL_FILE_REQUEST)
        }.apply { id = R.id.inkpilot_model_import })
        body.addView(StudioUi.text(activity, "Only the pinned LiquidAI/LFM3-1.7B-QAD-Q4_0 GGUF with its exact SHA-256 is accepted for local evaluation. Drafting stays off.", 14f))
        body.addView(StudioUi.text(activity, "Model qualification is pending. Automatic drafting is off; staff should reply directly in SMS.", 14f))
        body.addView(StudioUi.text(activity, "Incoming SMS and staff attention stay available. Check Drafts for replies that need a person.", 14f))
        val enabled = SwitchCompat(activity).apply {
            id = R.id.inkpilot_agent_enabled
            text = "Draft replies automatically"
            setTextColor(StudioUi.ink)
            isEnabled = false
        }
        body.addView(enabled)
        val retry = StudioUi.button(activity, "Draft pending messages") {
            work.load({ AgentRuntime.retryPending(activity.applicationContext) }) {
                StudioUi.error(activity, "Drafting pending messages. Open Drafts to review when ready.")
                refresh()
            }
        }.apply { isEnabled = false }
        body.addView(retry)
        work.load({ settings.enabled }) { value ->
            enabled.isChecked = value
            enabled.isEnabled = AgentRuntime.BOUNDED_INTENT_QUALIFIED && (settings.modelName != null || value)
            retry.isEnabled = AgentRuntime.BOUNDED_INTENT_QUALIFIED && value && settings.modelName != null
            enabled.setOnCheckedChangeListener { _, checked ->
                enabled.isEnabled = false
                work.load({ settings.enabled = checked; AgentRuntime.runPending(activity.applicationContext) }) { refresh() }
            }
        }
        body.addView(StudioUi.button(activity, "Review undelivered SMS and held replies") {
            AgentInboundReview(activity, work).open()
        })
        body.addView(StudioUi.button(activity, "Refresh status", refresh))
        return StudioUi.scroll(activity, body)
    }

    fun soul(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Soul & studio memory", 24f, true))
        body.addView(StudioUi.text(activity, "Your assistant’s tone and studio facts: services, hours, pricing, and booking policies. Client-specific notes live in Contacts.", 15f))
        val settings = AgentSettings(activity.applicationContext)
        val editor = EditText(activity).apply {
            id = R.id.inkpilot_soul
            hint = "Write the information staff want it to use."
            minLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(4_000))
            setTextColor(StudioUi.ink)
            setHintTextColor(StudioUi.muted)
            isEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(value: Editable?) { soulDraft = value?.toString() }
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        body.addView(editor)
        val save = StudioUi.button(activity, "Save studio memory") {
            val value = editor.text.toString()
            work.load({ settings.soul = value }) { StudioUi.error(activity, "Studio memory saved.") }
        }.apply { isEnabled = false }
        body.addView(save)
        work.load({ settings.soul }) { value ->
            editor.setText(soulDraft ?: value)
            editor.isEnabled = true
            save.isEnabled = true
        }
        return StudioUi.scroll(activity, body)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != MODEL_FILE_REQUEST) return false
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null) {
            StudioUi.error(activity, "Importing model. Keep inkPilot open until it finishes.")
            work.load({ AgentRuntime.importModel(activity.applicationContext, uri) }) {
                StudioUi.error(activity, "Model imported: $it")
                refresh()
            }
        }
        return true
    }

    companion object { private const val MODEL_FILE_REQUEST = 7302 }
}
