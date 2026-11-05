package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.widget.ScrollView

/** Loading placeholders must not consume and clamp the restored list position. */
internal class StudioScrollView(context: Context, private var awaitingContent: Boolean = false) : ScrollView(context) {
    private var pendingPosition: IntArray? = null

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle && state.containsKey("studio.scroll.parent")) {
            super.onRestoreInstanceState(state.getParcelable<Parcelable>("studio.scroll.parent"))
            pendingPosition = intArrayOf(state.getInt("x"), state.getInt("y"))
            requestLayout()
        } else super.onRestoreInstanceState(state)
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("studio.scroll.parent", super.onSaveInstanceState())
        putInt("x", pendingPosition?.get(0) ?: scrollX)
        putInt("y", pendingPosition?.get(1) ?: scrollY)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!awaitingContent) pendingPosition?.let {
            // Apply after layout, even if the placeholder already completed its first layout.
            scrollTo(it[0], it[1])
            pendingPosition = null
        }
    }

    /** Called after async rows (including an empty result) have replaced the placeholder. */
    fun contentReady() {
        awaitingContent = false
        requestLayout()
    }
}
