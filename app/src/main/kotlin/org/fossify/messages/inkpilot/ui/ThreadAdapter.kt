package org.fossify.messages.inkpilot.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.messaging.key
import org.fossify.messages.models.Message

internal class ThreadAdapter(private val activity: android.app.Activity) : RecyclerView.Adapter<ThreadAdapter.Row>(), StudioMessageRows {
    private val rows = mutableListOf<Message>()
    init { setHasStableIds(true); stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    class Row(val body: LinearLayout) : RecyclerView.ViewHolder(body)
    override fun messageKey(position: Int) = rows.getOrNull(position)?.key()
    override fun messagePosition(key: org.fossify.messages.messaging.MessageKey) = rows.indexOfFirst { it.key() == key }
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].getStableId()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(StudioUi.column(activity).apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })
    override fun onBindViewHolder(holder: Row, position: Int) {
        val message = rows[position]
        val body = holder.body
        body.removeAllViews()
        body.gravity = if (message.isReceivedMessage()) Gravity.START else Gravity.END
        val bubble = StudioUi.column(activity, 12).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (message.isReceivedMessage()) android.graphics.Color.rgb(232, 222, 206) else android.graphics.Color.rgb(231, 207, 207))
                cornerRadius = StudioUi.dp(body, 12).toFloat()
            }
        }
        body.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        bubble.addView(StudioUi.text(activity, message.body, 17f).apply { maxWidth = StudioUi.dp(body, 280) })
        message.attachment?.attachments?.forEach { attachment ->
            if (attachment.mimetype.startsWith("image/")) {
                val image = android.widget.ImageView(activity).apply {
                    contentDescription = "MMS image"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                }
                bubble.addView(image, LinearLayout.LayoutParams(StudioUi.dp(body, 240), StudioUi.dp(body, 180)))
                com.bumptech.glide.Glide.with(activity).load(attachment.getUri()).into(image)
            }
            bubble.addView(StudioUi.button(activity, attachment.filename.ifBlank { attachment.mimetype }) {
                val intent = attachment.viewIntent()
                runCatching { activity.startActivity(intent) }.onFailure { StudioUi.error(activity, "No app can open this attachment.") }
            })
        }
        val state = message.deliveryLabel()
        body.addView(StudioUi.text(activity,
            "${if (message.isMMS) "MMS" else "SMS"} · ${android.text.format.DateFormat.format("MMM d, h:mm a", message.millis())} $state", 12f))
        body.contentDescription = "${if (message.isReceivedMessage()) "Received" else "Outgoing"} ${if (message.isMMS) "MMS" else "SMS"}"
    }
    fun append(items: List<Message>) {
        val start = rows.size
        rows += items.filter { item -> rows.none { it.key() == item.key() } }
        notifyItemRangeInserted(start, rows.size - start)
    }
}
