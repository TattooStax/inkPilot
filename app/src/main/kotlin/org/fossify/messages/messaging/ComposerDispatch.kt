package org.fossify.messages.messaging

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Durable compare-and-claim is serialized; carrier work never holds the storage lock. */
fun ComposerStore.submit(
    request: OutgoingMessage, dispatcher: MessageDispatcher, unavailableUris: Set<Uri> = emptySet()
): DispatchOutcome {
    val rejected = locked {
        when {
            isUnknown(request.threadId) -> DispatchOutcome.Rejected("Previous send outcome unknown. Check the conversation.")
            wasSubmitted(request) -> DispatchOutcome.Rejected("This composer was already submitted")
            !saveUnclaimed(request) -> DispatchOutcome.Rejected("Unable to save composer")
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

internal fun ComposerStore.saveUnclaimed(request: OutgoingMessage): Boolean {
    if (isUnknown(request.threadId) || wasSubmitted(request)) return false
    val removed = load(request.threadId)?.attachmentUris.orEmpty() - request.attachmentUris.toSet()
    val saved = prefs.edit().putString(request.threadId.toString(), JSONObject()
        .put("attemptId", request.attemptId).put("recipient", request.recipient)
        .put("body", request.body).put("subscriptionId", request.subscriptionId)
        .put("uris", JSONArray(request.attachmentUris.map { it.toString() })).toString()).commit()
    if (saved) {
        appContext.settleSharedComposerImages(request.attachmentUris)
        appContext.discardSharedComposerImages(removed)
    }
    return saved
}
