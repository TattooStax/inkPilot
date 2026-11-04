package org.fossify.messages.inkpilot.ui

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.models.Conversation

internal class InboxAdapter(private val activity: android.app.Activity, private val open: (Long) -> Unit) : RecyclerView.Adapter<InboxAdapter.Row>() {
    private val rows = mutableListOf<Conversation>()
    init { setHasStableIds(true); stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    class Row(val text: TextView) : RecyclerView.ViewHolder(text)
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].threadId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(StudioUi.text(activity, "", 17f).apply {
        setPadding(24, 20, 24, 20)
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })
    override fun onBindViewHolder(holder: Row, position: Int) {
        val row = rows[position]
        holder.text.text = "${if (row.read) "" else "● "}${row.title}\n${row.snippet}"
        holder.text.setOnClickListener { open(row.threadId) }
    }
    fun append(items: List<Conversation>) {
        val start = rows.size
        rows += items.filter { item -> rows.none { it.threadId == item.threadId } }
        notifyItemRangeInserted(start, rows.size - start)
    }
}
