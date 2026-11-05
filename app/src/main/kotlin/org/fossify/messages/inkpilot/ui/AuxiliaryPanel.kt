package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.fossify.messages.inkpilot.data.StudioArchiveScope
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.restoreAllMessagesFromRecycleBinForConversation
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.models.Conversation

private const val EXPORT_NOTICE_TEXT_SP = 14f

/** The shell's small message utility surfaces; no inherited Activity is launched. */
internal class AuxiliaryPanel(private val activity: Activity, private val work: StudioWork,
                              private val open: (StudioRoute) -> Unit, private val refresh: () -> Unit,
                              private val exportStudio: () -> Unit) {
    fun settings(): android.view.View = screen("Messaging settings") { body ->
        val config = activity.config
        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            fun caption() = "$label: ${if (get()) "On" else "Off"}"
            val button = StudioUi.button(activity, caption()) {}
            button.setOnClickListener { set(!get()); button.text = caption() }
            body.addView(button)
        }
        toggle("Recycle bin for deleted messages", { config.useRecycleBin }) { config.useRecycleBin = it }
        toggle("Delivery reports", { config.enableDeliveryReports }) { config.enableDeliveryReports = it }
        toggle("Long messages as MMS", { config.sendLongMessageMMS }) { config.sendLongMessageMMS = it }
        toggle("Group messages as MMS", { config.sendGroupMessageMMS }) { config.sendGroupMessageMMS = it }
        body.addView(StudioUi.button(activity, "Lock screen message preview") {
            val choices = arrayOf("Hide sender and message", "Show sender", "Show sender and message")
            val values = intArrayOf(org.fossify.messages.helpers.LOCK_SCREEN_NOTHING,
                org.fossify.messages.helpers.LOCK_SCREEN_SENDER, org.fossify.messages.helpers.LOCK_SCREEN_SENDER_MESSAGE)
            androidx.appcompat.app.AlertDialog.Builder(activity).setItems(choices) { _, choice -> config.lockScreenVisibilitySetting = values[choice] }.show()
        })
        body.addView(StudioUi.button(activity, "Search conversations") { open(StudioRoute.Auxiliary("search")) })
        body.addView(StudioUi.button(activity, "Archived conversations") { open(StudioRoute.Auxiliary("archive")) })
        body.addView(StudioUi.button(activity, "Recycle bin") { open(StudioRoute.Auxiliary("recycle")) })
        body.addView(StudioUi.text(activity, StudioArchiveScope.DESCRIPTION, EXPORT_NOTICE_TEXT_SP))
        body.addView(StudioUi.button(activity, "Export Studio records and images") { exportStudio() })
        body.addView(StudioUi.button(activity, "Android app permissions") {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        })
        body.addView(StudioUi.button(activity, "Notification settings") {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        })
    }

    fun archive(): android.view.View = screen("Archived conversations") { body ->
        work.load({ activity.conversationsDB.getAllArchived() }) { rows ->
            if (rows.isEmpty()) body.addView(StudioUi.text(activity, "Nothing archived."))
            rows.sortedByDescending { it.date }.forEach { conversation ->
                body.addView(StudioUi.button(activity, conversation.title.ifBlank { "Conversation" }) {
                    open(StudioRoute.Auxiliary("details:${conversation.threadId}"))
                })
            }
        }
    }

    fun recycle(): android.view.View = screen("Recycle bin") { body ->
        work.load({ activity.messagesDB.getAllRecycleBinMessages().groupBy { it.threadId } }) { groups ->
            if (groups.isEmpty()) body.addView(StudioUi.text(activity, "Nothing in the recycle bin."))
            groups.forEach { (threadId, messages) ->
                body.addView(StudioUi.button(activity, "Conversation $threadId · ${messages.size} messages") {
                    open(StudioRoute.Auxiliary("recycle:$threadId"))
                })
            }
        }
    }

    fun recycledThread(threadId: Long): android.view.View = screen("Recycled messages") { body ->
        work.load({ activity.messagesDB.getThreadMessagesFromRecycleBin(threadId) }) { rows ->
            rows.forEach { body.addView(StudioUi.text(activity, it.body.ifBlank { "Attachment" })) }
            if (rows.isNotEmpty()) body.addView(StudioUi.button(activity, "Restore these messages") {
                work.load({ activity.restoreAllMessagesFromRecycleBinForConversation(threadId) }) { refresh() }
            })
        }
    }

    fun details(threadId: Long): android.view.View = screen("Conversation details") { body ->
        work.load({ activity.conversationsDB.getConversationWithThreadId(threadId) }) { row ->
            if (row == null) body.addView(StudioUi.text(activity, "Conversation unavailable."))
            else details(body, row)
        }
    }

    private fun details(body: LinearLayout, row: Conversation) {
        body.addView(StudioUi.text(activity, row.title.ifBlank { "Conversation" }, 20f, true))
        body.addView(StudioUi.text(activity, row.phoneNumber))
        body.addView(StudioUi.button(activity, "Open conversation") { open(StudioRoute.Thread(row.threadId)) })
        body.addView(StudioUi.button(activity, if (row.isArchived) "Unarchive" else "Archive") {
            work.load({ activity.updateConversationArchivedStatus(row.threadId, !row.isArchived) }) { refresh() }
        })
        body.addView(StudioUi.button(activity, "Client reference images") { open(StudioRoute.ImageLinks(threadId = row.threadId)) })
    }

    fun search(): android.view.View = screen("Search") { body ->
        val query = EditText(activity).apply { hint = "Conversation name or number"; inputType = InputType.TYPE_CLASS_TEXT }
        body.addView(query)
        val results = StudioUi.column(activity, 0)
        body.addView(StudioUi.button(activity, "Search") {
            results.removeAllViews()
            val term = query.text.toString().trim()
            if (term.isNotEmpty()) work.load({ activity.conversationsDB.getConversationsWithText("%$term%") }) { rows ->
                if (rows.isEmpty()) results.addView(StudioUi.text(activity, "No cached conversations found."))
                rows.forEach { row -> results.addView(StudioUi.button(activity, row.title) { open(StudioRoute.Thread(row.threadId)) }) }
            }
        })
        body.addView(results)
    }

    fun unsupported(): android.view.View = screen("Messages") { body ->
        body.addView(StudioUi.text(activity, "This item is unavailable here."))
    }

    private fun screen(title: String, fill: (LinearLayout) -> Unit): android.view.View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, title, 22f, true))
        fill(body)
        return StudioUi.scroll(activity, body)
    }
}
