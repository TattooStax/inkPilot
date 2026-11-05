package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.MessageDispatcher
import org.fossify.messages.messaging.MmsComposerClaim
import org.fossify.messages.messaging.OutgoingMessage
import org.fossify.messages.messaging.inShellMmsRejection
import org.fossify.messages.messaging.isMmsReportedFailed
import org.fossify.messages.messaging.hasCompletedCompose
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.models.Attachment
import android.widget.EditText
import android.widget.TextView
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.extensions.getThreadId

private const val MAX_IN_SHELL_TEXT_CHARS = 2_000

@SuppressLint("MissingPermission")
private fun AppCompatActivity.activeComposerSubscriptions(): Set<Int>? {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED) return null
    return try {
        getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .map { it.subscriptionId }.toSet()
    } catch (_: SecurityException) { null }
}

internal fun prepareInShellComposerSend(
    activity: AppCompatActivity, store: ComposerStore, value: OutgoingMessage,
    activeSimIds: () -> Set<Int>? = { activity.activeComposerSubscriptions() },
): Result<DispatchOutcome> {
    if (value.body.length > MAX_IN_SHELL_TEXT_CHARS) {
        return Result.success(DispatchOutcome.Rejected("Message exceeds 2,000 characters"))
    }
    val rejection = activity.inShellMmsRejection(value.attachmentUris)
    if (rejection != null) return Result.success(DispatchOutcome.Rejected(rejection))
    val active = try { activeSimIds() } catch (_: SecurityException) { null }
        ?: return Result.success(DispatchOutcome.Rejected("Allow SIM access, then choose a SIM"))
    if (value.subscriptionId !in active) {
        return Result.success(DispatchOutcome.Rejected("Choose an active SIM"))
    }
    return prepareComposerSend(store, value) {
        MessageDispatcher.local(activity) { outgoing ->
            when {
                outgoing.subscriptionId !in active -> DispatchOutcome.Rejected("Choose an active SIM")
                outgoing.attachmentUris.size > 1 -> DispatchOutcome.Rejected("Send one image per message")
                else -> activity.sendMessageCompat(
                    outgoing.body, outgoing.recipient.split("|").filter(String::isNotBlank),
                    outgoing.subscriptionId,
                    outgoing.attachmentUris.map { uri ->
                        Attachment(null, 0, uri.toString(),
                            activity.contentResolver.getType(uri) ?: "application/octet-stream",
                            0, 0, uri.lastPathSegment.orEmpty())
                    },
                    mmsComposerClaim = MmsComposerClaim(outgoing.threadId, outgoing.attemptId),
                )
            }
        }
    }
}

internal fun initialComposerStatus(store: ComposerStore, threadId: Long): String = when {
    store.hasCompletedCompose(threadId) -> "Message submitted. Open the conversation for its result."
    store.isMmsReportedFailed(threadId) ->
        "Carrier reported MMS failed. Check the conversation; this draft cannot be sent again."
    store.isUnknown(threadId) ->
        "Send outcome unknown. Check the conversation; this draft cannot be sent again."
    else -> ""
}

internal fun loadRecipientForThread(
    activity: AppCompatActivity, work: StudioWork, store: ComposerStore, threadId: Long,
    savedRecipient: String, recipient: EditText, persist: () -> Unit,
) {
    if (threadId <= 0L || savedRecipient.isNotBlank()) return
    work.load({ activity.getThreadParticipants(threadId, null).getAddresses()
        .filter(String::isNotBlank).joinToString("|") }) { loaded ->
        if (recipient.text.isBlank() && !store.isUnknown(threadId)) {
            recipient.setText(loaded)
            persist()
        }
    }
}

internal fun onUnknownComposerOutcome(
    store: ComposerStore, threadId: Long, status: TextView, refresh: () -> Unit,
) {
    if (!store.isUnknown(threadId)) refresh()
    else status.text = "Send outcome unknown. Draft retained. Check the conversation before composing another message."
}

internal fun openSentConversationIfNew(
    activity: AppCompatActivity, work: StudioWork, threadId: Long, request: OutgoingMessage,
    open: (StudioRoute) -> Unit,
) {
    if (threadId > 0L) return
    val recipients = request.recipient.split("|").filter(String::isNotBlank).toSet()
    work.load({ activity.getThreadId(recipients) }) { open(StudioRoute.Thread(it)) }
}
