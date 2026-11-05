package org.fossify.messages.inkpilot.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide

private const val ATTACHMENT_TITLE_SP = 22f
private const val ATTACHMENT_TYPE_SP = 14f
private const val IMAGE_HEIGHT_DP = 420

/** Message attachments stay in the conversation's shell stack. */
internal class AttachmentPanel(private val activity: android.app.Activity, private val back: () -> Unit) {
    fun view(attachment: StudioRoute.Attachment): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.button(activity, "‹ Conversation", back))
        body.addView(StudioUi.text(activity, attachment.filename.ifBlank { "Attachment" }, ATTACHMENT_TITLE_SP, true))
        body.addView(StudioUi.text(activity, attachment.mimetype.ifBlank { "Unknown file type" }, ATTACHMENT_TYPE_SP))
        if (attachment.mimetype.startsWith("image/")) {
            val image = ImageView(activity).apply {
                contentDescription = attachment.filename.ifBlank { "Message image" }
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            body.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, StudioUi.dp(body, IMAGE_HEIGHT_DP)))
            Glide.with(activity).load(attachment.uri).into(image)
        } else {
            body.addView(StudioUi.text(activity,
                "Preview for this attachment type is unavailable. The file remains in the conversation."))
        }
        return StudioUi.scroll(activity, body)
    }
}
