package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.OutgoingMessage

internal class ComposerSendControls(
    val status: TextView, val send: View, val request: () -> OutgoingMessage,
    val enableEditor: (Boolean) -> Unit, val refresh: () -> Unit, val open: (StudioRoute) -> Unit,
    val accepted: (Long) -> Unit,
)

internal class InShellSendController(
    private val activity: AppCompatActivity, private val work: StudioWork,
    private val store: ComposerStore, private val threadId: Long,
    private val ui: ComposerSendControls,
) {
    fun onSend() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) {
            ui.status.text = "Choose a SIM after allowing SIM access"
            return
        }
        val value = ui.request()
        if (value.body.isBlank() && value.attachmentUris.isEmpty()) {
            ui.status.text = "Write a message or attach a file"
            return
        }
        ui.send.isEnabled = false
        ui.enableEditor(false)
        work.load({ prepareInShellComposerSend(activity, store, value) }) { result ->
            val outcome = result.getOrElse {
                if (store.isUnknown(threadId)) onUnknownComposerOutcome(store, threadId, ui.status, ui.refresh)
                else ui.status.text = "Unable to prepare message. Draft retained."
                reenable(value)
                return@load
            }
            onOutcome(value, outcome)
        }
    }

    private fun onOutcome(value: OutgoingMessage, outcome: DispatchOutcome) {
        when (outcome) {
            is DispatchOutcome.Accepted -> {
                if (threadId > 0L) ui.accepted(threadId)
                else openSentConversationIfNew(activity, work, threadId, value, ui.open)
            }
            is DispatchOutcome.Rejected -> {
                ui.status.text = outcome.reason
                reenable(value)
            }
            is DispatchOutcome.Unknown ->
                onUnknownComposerOutcome(store, threadId, ui.status, ui.refresh)
        }
    }

    private fun reenable(value: OutgoingMessage) {
        ui.send.isEnabled = composerCanRetry(store, value)
        ui.enableEditor(ui.send.isEnabled)
    }
}
