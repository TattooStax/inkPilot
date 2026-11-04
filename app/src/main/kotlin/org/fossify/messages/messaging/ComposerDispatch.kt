package org.fossify.messages.messaging

import android.net.Uri

/** One production path commits the full composer before any possible transport side effect. */
fun ComposerStore.submit(
    request: OutgoingMessage, dispatcher: MessageDispatcher, unavailableUris: Set<Uri> = emptySet()
): DispatchOutcome {
    if (!save(request)) return DispatchOutcome.Rejected("Unable to save composer")
    val outcome = if (request.attachmentUris.any { it in unavailableUris }) {
        DispatchOutcome.Rejected("Attachment unavailable. Remove or reattach it to send.")
    } else dispatcher.submit(request)
    complete(request.threadId, outcome)
    return outcome
}
