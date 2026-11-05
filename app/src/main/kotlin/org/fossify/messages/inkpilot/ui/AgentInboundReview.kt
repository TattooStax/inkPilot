package org.fossify.messages.inkpilot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.runtime.DirectReplyPendingStore
import org.fossify.messages.inkpilot.runtime.FilteredSmsJournal

/** Keyset pages keep every private arrival and held reply available until staff disposition. */
internal class AgentInboundReview(private val activity: AppCompatActivity, private val work: StudioWork) {
    fun open() {
        if (activity.appLockManager.isLocked()) return
        AlertDialog.Builder(activity).setTitle("SMS needing staff review")
            .setItems(arrayOf("Filtered or undelivered arrivals", "Held direct replies")) { _, selected ->
                if (selected == 0) filtered() else replies()
            }.setNegativeButton("Close", null).show()
    }

    private fun filtered(before: FilteredSmsJournal.Cursor? = null) {
        work.load({ FilteredSmsJournal(activity).use { it.page(before) } }) { page ->
            if (activity.appLockManager.isLocked()) return@load
            val rows = page.entries
            if (rows.isEmpty()) { StudioUi.error(activity, "No more filtered arrivals."); return@load }
            val dialog = AlertDialog.Builder(activity).setTitle("Filtered or undelivered SMS")
                .setItems(rows.map { "${it.reason}: ${it.sender}" }.toTypedArray()) { _, index ->
                    val row = rows[index]
                    detail("${row.reason}: ${row.sender}", row.body) {
                        work.load({ FilteredSmsJournal(activity).use { it.remove(row.receipt) } }) { filtered(before) }
                    }
                }.setNegativeButton("Close", null).setNeutralButton("Back") { _, _ -> open() }
            if (page.next != null) dialog.setPositiveButton("Older") { _, _ -> filtered(page.next) }
            dialog.show()
        }
    }

    private fun replies(before: DirectReplyPendingStore.Cursor? = null) {
        work.load({ DirectReplyPendingStore(activity).page(before) }) { page ->
            if (activity.appLockManager.isLocked()) return@load
            val rows = page.entries
            if (rows.isEmpty()) { StudioUi.error(activity, "No more held replies."); return@load }
            val dialog = AlertDialog.Builder(activity).setTitle("Held direct replies")
                .setItems(rows.map { "${it.recipient} · thread ${it.threadId}" }.toTypedArray()) { _, index ->
                    val row = rows[index]
                    detail("Held reply: ${row.recipient}", row.body) {
                        work.load({ DirectReplyPendingStore(activity).complete(row.id) }) { replies(before) }
                    }
                }.setNegativeButton("Close", null).setNeutralButton("Back") { _, _ -> open() }
            if (page.next != null) dialog.setPositiveButton("Older") { _, _ -> replies(page.next) }
            dialog.show()
        }
    }

    private fun detail(title: String, body: String, dismiss: () -> Unit) {
        if (activity.appLockManager.isLocked()) return
        AlertDialog.Builder(activity).setTitle(title).setMessage(body)
            .setPositiveButton("Copy text") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("SMS needing review", body))
            }.setNeutralButton("Dismiss record") { _, _ ->
                AlertDialog.Builder(activity).setTitle("Remove this review record?")
                    .setMessage("This removes the private review copy. It does not send or delete a native SMS.")
                    .setPositiveButton("Remove") { _, _ -> dismiss() }
                    .setNegativeButton("Keep", null).show()
            }.setNegativeButton("Close", null).show()
    }
}
