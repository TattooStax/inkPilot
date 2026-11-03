package org.fossify.messages.inkpilot.studio

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.database.sqlite.SQLiteException
import java.io.IOException
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fossify.commons.extensions.appLockManager
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal class StudioPanelHost(
    val activity: AppCompatActivity,
    private val executor: Executor,
    val onError: (String) -> Unit,
) {
    val ink = Color.parseColor("#25211E")
    val accent = Color.parseColor("#782D34")
    val paper = Color.parseColor("#F4EBDD")
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun body(content: LinearLayout): View = ScrollView(activity).apply {
        isFillViewport = true
        setBackgroundColor(paper)
        content.setPadding(dp(18), dp(8), dp(18), dp(24))
        addView(content)
    }
    fun text(value: String, size: Float = 16f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(0, dp(8), 0, dp(8))
    }
    fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label; isAllCaps = false; setTextColor(accent); setOnClickListener { action() }
        minHeight = dp(48)
        background = GradientDrawable().apply {
            setColor(paper); setStroke(dp(1), Color.parseColor("#D3C7B8")); cornerRadius = dp(3).toFloat()
        }
    }
    fun field(hintText: String, value: String = "") = EditText(activity).apply {
        hint = hintText; setText(value); setTextColor(ink); textSize = 16f; maxLines = 3
        setHintTextColor(Color.parseColor("#695F57"))
    }
    fun onUi(action: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed && !activity.appLockManager.isLocked()) action()
    }
    fun <T> load(work: () -> T, show: (T) -> Unit) {
        try {
            executor.execute {
                try { val result = work(); onUi { show(result) } }
                catch (error: Exception) {
                    val message = when (error) {
                        is SQLiteException -> "This change could not be saved. Check for a duplicate name and try again."
                        is IOException -> "The image could not be read or saved."
                        else -> error.message ?: "This change could not be saved."
                    }
                    onUi { onError(message) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The activity has ended; no work was accepted and no UI remains to update.
        }
    }
}
