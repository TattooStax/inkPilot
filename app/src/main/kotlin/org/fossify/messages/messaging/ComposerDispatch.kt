package org.fossify.messages.messaging

import android.net.Uri

/** Durable compare-and-claim is serialized; carrier work never holds the storage lock. */
fun ComposerStore.submit(
    request: OutgoingMessage, dispatcher: MessageDispatcher, unavailableUris: Set<Uri> = emptySet()
): DispatchOutcome {
    val rejected = locked {
        when {
            isUnknown(request.threadId) -> DispatchOutcome.Rejected("Previous send outcome unknown. Check the conversation.")
            wasSubmitted(request) -> DispatchOutcome.Rejected("This composer was already submitted")
            !save(request) -> DispatchOutcome.Rejected("Unable to save composer")
            !claim(request) -> DispatchOutcome.Rejected("This composer was already submitted")
            else -> null
        }
    }
    if (rejected != null) return rejected
    val outcome = if (request.attachmentUris.any { it in unavailableUris }) {
        DispatchOutcome.Rejected("Attachment unavailable. Remove or reattach it to send.")
    } else dispatcher.submit(request)
    locked { finish(request, outcome) }
    return outcome
}
