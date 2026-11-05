package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.fossify.commons.extensions.appLockManager

internal object StudioUi {
    val paper = Color.rgb(244, 235, 221)
    val ink = Color.rgb(37, 33, 30)
    val muted = Color.rgb(105, 95, 87)
    val accent = Color.rgb(120, 45, 52)
    val rule = Color.rgb(211, 199, 184)

    fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()

    fun column(activity: Activity, padding: Int = 20) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(paper)
        val inset = dp(this, padding)
        setPadding(inset, inset, inset, inset)
    }

    fun text(activity: Activity, value: String, size: Float = 16f, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(this, 6), 0, dp(this, 6))
    }

    fun button(activity: Activity, label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(accent)
        minHeight = dp(this, 48)
        val border = dp(this, 1)
        val radius = dp(this, 3).toFloat()
        background = GradientDrawable().apply {
            setColor(paper)
            setStroke(border, rule)
            cornerRadius = radius
        }
        setOnClickListener { action() }
    }

    fun rule(activity: Activity) = View(activity).apply {
        setBackgroundColor(rule)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1))
    }

    fun scroll(activity: Activity, column: LinearLayout, loading: Boolean = false) = StudioScrollView(activity, loading).apply {
        isFillViewport = true
        addView(column)
    }

    fun error(activity: Activity, message: String) {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}

/** All reads/imports use the host queue; callbacks stop when their activity is gone. */
internal class StudioWork(private val activity: Activity, private val executor: Executor) {
    fun <T> load(task: () -> T, failed: (Throwable) -> Unit = {}, done: (T) -> Unit) {
        try {
            executor.execute {
                val result = try { Result.success(task()) } catch (error: Exception) { Result.failure(error) }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && !activity.appLockManager.isLocked()) {
                        result.fold(done) { failed(it); StudioUi.error(activity, it.message ?: "Couldn't complete that. Please try again.") }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The activity has closed; it no longer accepts work.
        }
    }
}
