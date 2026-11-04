package org.fossify.messages.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import java.util.UUID

/** Preflight every attachment before any handoff. No retry or text-only fallback. */
class MessageDispatcher(
    private val readable: (Uri) -> Boolean,
    private val canSend: () -> Boolean,
    private val handoff: (OutgoingMessage) -> DispatchOutcome
) {
    fun submit(request: OutgoingMessage): DispatchOutcome {
        if (request.recipient.isBlank()) return DispatchOutcome.Rejected("Missing recipient")
        if (request.subscriptionId < 0) return DispatchOutcome.Rejected("Select an active SIM")
        return try {
            if (!canSend()) return DispatchOutcome.Rejected("SMS permission denied")
            if (request.attachmentUris.any { !readable(it) }) return DispatchOutcome.Rejected("Attachment is unreadable")
            try { handoff(request) } catch (_: Exception) { DispatchOutcome.Unknown(UUID.randomUUID().toString()) }
        } catch (_: Exception) {
            DispatchOutcome.Rejected("Unable to prepare message")
        }
    }

    companion object {
        fun local(context: Context, handoff: (OutgoingMessage) -> DispatchOutcome) = MessageDispatcher(
            readable = { uri -> context.contentResolver.openInputStream(uri)?.use { it.read(); true } ?: false },
            canSend = { ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED },
            handoff = handoff
        )
    }
}
