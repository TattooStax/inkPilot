package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import androidx.recyclerview.widget.RecyclerView

/** Restore only after the page containing the saved anchor exists, including repeated rotations. */
internal class StudioPagedList(context: Context) : RecyclerView(context) {
    var loadMore: () -> Unit = {}
    private var pending: Parcelable? = null
    private var targetRows = 0
    private var loadedRows = 0
    private var hasMore = true

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle && state.containsKey("studio.list.parent")) {
            pending = state.getParcelable("studio.list.parent")
            targetRows = state.getInt("rows")
            restoreWhenReady()
        } else super.onRestoreInstanceState(state)
    }
    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("studio.list.parent", pending ?: super.onSaveInstanceState())
        putInt("rows", maxOf(targetRows, loadedRows))
    }
    fun contentReady(rows: Int, more: Boolean) {
        loadedRows = rows
        hasMore = more
        restoreWhenReady()
    }
    private fun restoreWhenReady() {
        val state = pending ?: return
        if (loadedRows < targetRows && hasMore) {
            loadMore()
        } else {
            pending = null
            targetRows = 0
            super.onRestoreInstanceState(state)
        }
    }
}
