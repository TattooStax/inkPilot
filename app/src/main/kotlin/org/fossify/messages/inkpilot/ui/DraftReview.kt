package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.ReviewDraft
import org.fossify.messages.inkpilot.runtime.AgentRuntime

internal class DraftReview(
    private val activity: AppCompatActivity,
    private val work: StudioWork,
    private val refresh: () -> Unit,
) {
    fun show(draft: ReviewDraft) {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "To: ${draft.recipient}", 16f, true))
        body.addView(StudioUi.text(activity, "Client: ${draft.inboundText}", 14f))
        val editor = EditText(activity).apply {
            id = R.id.inkpilot_draft_body
            setText(draft.body)
            minLines = 4
            maxLines = 10
            filters = arrayOf(InputFilter.LengthFilter(800))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(StudioUi.ink)
        }
        body.addView(editor)
        body.addView(StudioUi.text(activity, "Check the recipient and reply. You will choose the business SIM before sending.", 14f))
        val dialog = AlertDialog.Builder(activity).setTitle("Review reply").setView(StudioUi.scroll(activity, body))
            .setNegativeButton("Cancel", null).setPositiveButton("Choose SIM", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reply = editor.text.toString().trim()
                if (reply.isEmpty()) { editor.error = "Write a reply"; return@setOnClickListener }
                chooseSim(draft, reply) { dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun chooseSim(draft: ReviewDraft, body: String, dismiss: () -> Unit) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 7303)
            StudioUi.error(activity, "Allow SIM access, then choose the business SIM again.")
            return
        }
        work.load({ activity.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() }) { sims ->
            if (sims.isEmpty()) { StudioUi.error(activity, "No active SIM is available. The draft has been kept."); return@load }
            val labels = sims.map { "${it.displayName} · SIM ${it.simSlotIndex + 1}" }.toTypedArray()
            AlertDialog.Builder(activity).setTitle("Choose business SIM").setItems(labels) { _, index ->
                val sim = sims[index]
                AlertDialog.Builder(activity).setTitle("Approve & send?")
                    .setMessage("To ${draft.recipient}\nUsing ${labels[index]}\n\n$body")
                    .setNegativeButton("Cancel", null).setPositiveButton("Approve & send") { _, _ ->
                        dismiss()
                        work.load({ AgentRuntime.approveAndSend(activity.applicationContext, draft.draftId, body, sim.subscriptionId) }) {
                            StudioUi.error(activity, it)
                            refresh()
                        }
                    }.show()
            }.setNegativeButton("Cancel", null).show()
        }
    }
}
