package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE

internal class MessagePanel(
    private val activity: AppCompatActivity,
    private val work: StudioWork,
    private val setup: () -> Unit,
    private val drafts: () -> Unit,
) {
    fun messages(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.button(activity, "Drafts to review", drafts))
        if (Telephony.Sms.getDefaultSmsPackage(activity) != activity.packageName) {
            body.addView(StudioUi.text(activity, "Make inkPilot your SMS app to receive messages here.", 14f))
            body.addView(StudioUi.button(activity, "Set up SMS", setup).apply { id = R.id.inkpilot_sms_setup })
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (Telephony.Sms.getDefaultSmsPackage(activity) == activity.packageName) {
                body.addView(StudioUi.button(activity, "Allow message access", setup).apply { id = R.id.inkpilot_sms_setup })
            }
            return StudioUi.scroll(activity, body)
        }
        body.addView(StudioUi.button(activity, "New message") {
            activity.startActivity(Intent(activity, NewConversationActivity::class.java))
        }.apply { id = R.id.inkpilot_new_message })
        val list = StudioUi.column(activity, 0).apply { id = R.id.inkpilot_message_list }
        val loading = StudioUi.text(activity, "Loading messages…")
        list.addView(loading)
        body.addView(list)
        work.load({ activity.getConversations().take(150) }) { conversations ->
            list.removeAllViews()
            if (conversations.isEmpty()) list.addView(StudioUi.text(activity, "No messages yet."))
            conversations.forEach { conversation ->
                val row = StudioUi.column(activity, 0)
                row.addView(StudioUi.text(activity, conversation.title, 17f, !conversation.read))
                row.addView(StudioUi.text(activity, conversation.snippet, 14f).apply { maxLines = 2 })
                row.setPadding(0, StudioUi.dp(row, 8), 0, StudioUi.dp(row, 12))
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener {
                    activity.startActivity(Intent(activity, ThreadActivity::class.java)
                        .putExtra(THREAD_ID, conversation.threadId).putExtra(THREAD_TITLE, conversation.title))
                }
                list.addView(row)
                list.addView(StudioUi.rule(activity))
            }
        }
        return StudioUi.scroll(activity, body)
    }

    fun phone(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Make a call", 24f, true))
        val number = EditText(activity).apply {
            hint = "Phone number"
            contentDescription = "Phone number"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(StudioUi.ink)
            setHintTextColor(StudioUi.muted)
            minHeight = StudioUi.dp(this, 56)
        }
        body.addView(number)
        body.addView(StudioUi.button(activity, "Open dialer") {
            val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.text.toString().trim(), null))
            runCatching { activity.startActivity(intent) }.onFailure { StudioUi.error(activity, "No phone dialer is available.") }
        })
        body.addView(StudioUi.text(activity, "Calls use your phone’s SIM and native call screen.", 14f))
        return StudioUi.scroll(activity, body)
    }
}
