package org.fossify.messages.inkpilot.data

import inkpilot.agent.DraftRequest

/** An inactive staff review never grants authority to the unbounded RAW worker. */
fun StudioRepository.blockInactiveLeadScope(request: DraftRequest): Boolean {
    if (!database.hasStoredLeadIntake(request.eventId)) return false
    // The attempt writer checks the current source revision and records staff attention.
    // Even if the source changed before that check, skip this worker request.
    recordDraftAttempt(request.eventId, request.expectedRevision, "REVIEW_SCOPE_UNAVAILABLE")
    return true
}
