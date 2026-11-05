package org.fossify.messages.inkpilot.ui

import android.provider.Telephony.Sms
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.messaging.key
import org.fossify.messages.messaging.SmsPartJournal
import org.fossify.messages.messaging.SmsPartState
import org.fossify.messages.messaging.exactSmsRowType
import org.fossify.messages.messaging.partCount
import org.fossify.messages.models.Message

internal class StudioThreadAdapter(private val activity: android.app.Activity,
    private val forward: (Message) -> Unit = {},
    private val viewAttachment: (org.fossify.messages.models.Attachment) -> Unit = {},
    private val importImage: (org.fossify.messages.messaging.MessageKey, Long, org.fossify.messages.models.Attachment) -> Unit = { _, _, _ -> },
) : RecyclerView.Adapter<StudioThreadAdapter.Row>(), StudioMessageRows {
    private data class SmsBubbleVerdict(val state: SmsPartState? = null, val multipart: Boolean = false)
    private val rows = mutableListOf<Message>()
    private val smsPartJournal = SmsPartJournal(activity)
    init { setHasStableIds(true); stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        smsPartJournal.close()
        super.onDetachedFromRecyclerView(recyclerView)
    }
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
        fun showActions(): Boolean {
            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle("Message actions")
                .setPositiveButton("Forward") { _, _ -> forward(message) }
                .setNegativeButton("Cancel", null).show()
            return true
        }
        bubble.setOnLongClickListener { showActions() }
        bubble.addView(StudioUi.text(activity, message.body, 17f).apply {
            maxWidth = StudioUi.dp(body, 280)
            setOnLongClickListener { showActions() }
        })
        message.attachment?.attachments?.forEach { attachment ->
            if (attachment.mimetype.startsWith("image/")) {
                val image = android.widget.ImageView(activity).apply {
                    contentDescription = "MMS image"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                }
                bubble.addView(image, LinearLayout.LayoutParams(StudioUi.dp(body, 240), StudioUi.dp(body, 180)))
                com.bumptech.glide.Glide.with(activity).load(attachment.getUri()).into(image)
                if (message.key().origin == org.fossify.messages.messaging.MessageOrigin.MMS) {
                    bubble.addView(StudioUi.button(activity, "Save to Client References") {
                        importImage(message.key(), message.threadId, attachment)
                    })
                }
            }
            bubble.addView(StudioUi.button(activity, attachment.filename.ifBlank { attachment.mimetype }) {
                viewAttachment(attachment)
            })
        }
        val verdict = smsVerdict(message)
        val state = message.deliveryLabel(verdict.state, verdict.multipart)
        val direction = if (message.isReceivedMessage()) "Received" else "Outgoing"
        body.addView(StudioUi.text(activity,
            "$direction ${if (message.isMMS) "MMS" else "SMS"} · ${android.text.format.DateFormat.format("MMM d, h:mm a", message.millis())} $state", 12f))
    }
    private fun smsVerdict(message: Message): SmsBubbleVerdict {
        if (message.isMMS || message.isScheduled || message.isReceivedMessage()) return SmsBubbleVerdict()
        return runCatching {
            val partState = smsPartJournal.state(message.id, exactNativeType = { activity.exactSmsRowType(message.id) })
            val multipart = partState == SmsPartState.SENT && message.type == Sms.MESSAGE_TYPE_SENT &&
                message.status == Sms.STATUS_COMPLETE && smsPartJournal.partCount(message.id) != 1
            SmsBubbleVerdict(partState ?: staleUntrackedSmsOutcome(message.type, message.millis()), multipart)
        }.getOrElse { SmsBubbleVerdict(SmsPartState.UNKNOWN) }
    }
    fun append(items: List<Message>) {
        val start = rows.size
        rows += items.filter { item -> rows.none { it.key() == item.key() } }
        notifyItemRangeInserted(start, rows.size - start)
    }
}
