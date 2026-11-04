package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin

internal interface StudioMessageRows {
    fun messageKey(position: Int): MessageKey?
    fun messagePosition(key: MessageKey): Int
}

/** Preserve the visible provider identity and edge offset while new messages change positions. */
internal class StudioPagedList(context: Context) : RecyclerView(context) {
    var loadMore: () -> Unit = {}
    private var pending: Parcelable? = null
    private var pendingKey: MessageKey? = null
    private var pendingOffset = 0
    private var targetRows = 0
    private var loadedRows = 0
    private var hasMore = true

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle && state.containsKey("studio.list.parent")) {
            pending = state.getParcelable("studio.list.parent")
            pendingKey = state.getString("origin")?.let { origin ->
                MessageOrigin.entries.find { it.name == origin }?.let { MessageKey(it, state.getLong("message")) }
            }
            pendingOffset = state.getInt("offset")
            targetRows = state.getInt("rows")
            restoreWhenReady()
        } else super.onRestoreInstanceState(state)
    }
    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("studio.list.parent", pending ?: super.onSaveInstanceState())
        putInt("rows", maxOf(targetRows, loadedRows))
        var key = pendingKey
        var offset = pendingOffset
        if (pending == null) {
            val manager = layoutManager as? LinearLayoutManager
            val position = manager?.findFirstVisibleItemPosition() ?: NO_POSITION
            val child = manager?.findViewByPosition(position)
            key = (adapter as? StudioMessageRows)?.messageKey(position)
            if (child != null) offset = if (manager.reverseLayout) height - paddingBottom - manager.getDecoratedBottom(child)
                else manager.getDecoratedTop(child) - paddingTop
        }
        key?.let { putString("origin", it.origin.name); putLong("message", it.id); putInt("offset", offset) }
    }
    fun contentReady(rows: Int, more: Boolean) {
        loadedRows = rows
        hasMore = more
        restoreWhenReady()
    }
    private fun restoreWhenReady() {
        val state = pending ?: return
        val key = pendingKey
        val position = key?.let { (adapter as? StudioMessageRows)?.messagePosition(it) } ?: NO_POSITION
        if (hasMore && (if (key != null) position == NO_POSITION else loadedRows < targetRows)) {
            loadMore()
            return
        }
        pending = null
        pendingKey = null
        targetRows = 0
        if (position != NO_POSITION) (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, pendingOffset)
        else super.onRestoreInstanceState(state) // Deleted anchor or a non-message list.
    }
}
