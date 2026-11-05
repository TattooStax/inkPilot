package org.fossify.messages.inkpilot.ui

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.models.Conversation

private const val ROW_HORIZONTAL_DP = 16
private const val ROW_VERTICAL_DP = 10
private const val TITLE_SP = 16f
private const val PREVIEW_SP = 14f
private const val PREVIEW_TOP_DP = 3

internal class InboxAdapter(private val activity: android.app.Activity, private val open: (Long) -> Unit) : RecyclerView.Adapter<InboxAdapter.Row>() {
    private val rows = mutableListOf<Conversation>()
    init { setHasStableIds(true); stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    class Row(val root: LinearLayout, val title: TextView, val snippet: TextView) : RecyclerView.ViewHolder(root)
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].threadId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = StudioUi.dp(this, ROW_HORIZONTAL_DP)
            val vertical = StudioUi.dp(this, ROW_VERTICAL_DP)
            setPadding(horizontal, vertical, horizontal, vertical)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            isClickable = true
            isFocusable = true
        }
        val title = StudioUi.text(activity, "", TITLE_SP, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        }
        val snippet = StudioUi.text(activity, "", PREVIEW_SP).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(StudioUi.muted)
            setPadding(0, StudioUi.dp(this, PREVIEW_TOP_DP), 0, 0)
        }
        root.addView(title)
        root.addView(snippet)
        return Row(root, title, snippet)
    }
    override fun onBindViewHolder(holder: Row, position: Int) {
        val row = rows[position]
        holder.title.text = "${if (row.read) "" else "● "}${row.title}"
        holder.snippet.text = row.snippet.replace(Regex("\\s+"), " ").trim()
        holder.snippet.visibility = if (holder.snippet.text.isBlank()) View.GONE else View.VISIBLE
        holder.root.setOnClickListener { open(row.threadId) }
    }
    fun append(items: List<Conversation>) {
        val start = rows.size
        rows += items.filter { item -> rows.none { it.threadId == item.threadId } }
        notifyItemRangeInserted(start, rows.size - start)
    }
}
