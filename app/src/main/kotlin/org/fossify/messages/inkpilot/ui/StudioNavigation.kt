package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

internal fun navigation(activity: Activity, selected: StudioTab?,
    choose: (StudioTab) -> Unit) = LinearLayout(activity).apply {
    id = R.id.inkpilot_navigation
    orientation = LinearLayout.HORIZONTAL
    StudioTab.entries.forEach { tab ->
        addView(LinearLayout(activity).apply {
            id = tab.viewId
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = tab.label
            isClickable = true
            isFocusable = true
            isSelected = tab == selected
            minimumHeight = StudioUi.dp(this, 72)
            setPadding(StudioUi.dp(this, 2), StudioUi.dp(this, 8), StudioUi.dp(this, 2), StudioUi.dp(this, 5))
            addView(ImageView(activity).apply {
                setImageResource(tab.icon)
                setColorFilter(if (tab == selected) StudioUi.accent else StudioUi.ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(StudioUi.dp(this, 23), StudioUi.dp(this, 23)))
            addView(StudioUi.text(activity, tab.label, 12f, tab == selected).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(if (tab == selected) StudioUi.accent else StudioUi.ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { choose(tab) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, tab.widthWeight))
    }
}

internal fun showStudioDrawer(activity: Activity, open: (String) -> Unit) {
    val dialog = Dialog(activity)
    val body = StudioUi.column(activity)
    body.setBackgroundColor(StudioUi.paper)
    body.addView(StudioUi.text(activity, "inkPilot", 26f, true))
    body.addView(StudioUi.text(activity, "Your studio assistant", 14f))
    body.addView(StudioUi.rule(activity))
    listOf("Drafts & staff replies" to "drafts", "Action proposals" to "proposals",
        "Soul & studio memory" to "soul", "Local model" to "model").forEach { (label, key) ->
        body.addView(StudioUi.button(activity, label) { dialog.dismiss(); open(key) })
    }
    listOf("Messaging settings" to "settings", "Search" to "search", "Archived" to "archive", "Recycle bin" to "recycle").forEach { (label, key) ->
        body.addView(StudioUi.button(activity, label) { dialog.dismiss(); open(key) })
    }
    body.addView(StudioUi.button(activity, "Close") { dialog.dismiss() })
    dialog.setContentView(body)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setGravity(Gravity.START or Gravity.TOP)
        setLayout((activity.resources.displayMetrics.widthPixels * 0.84f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
    }
    dialog.show()
}
