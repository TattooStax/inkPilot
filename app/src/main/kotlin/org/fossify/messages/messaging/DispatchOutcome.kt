package org.fossify.messages.messaging

import android.net.Uri

data class OutgoingMessage(val threadId: Long, val recipient: String, val body: String,
    val attachmentUris: List<Uri>, val subscriptionId: Int)

/** Acceptance is a local queue acknowledgment, never a delivery verdict. */
sealed interface DispatchOutcome {
    data class Accepted(val providerKey: MessageKey? = null) : DispatchOutcome
    data class Rejected(val reason: String) : DispatchOutcome
    data class Unknown(val claimId: String) : DispatchOutcome
}
