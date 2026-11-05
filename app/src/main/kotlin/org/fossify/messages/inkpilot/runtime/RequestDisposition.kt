package org.fossify.messages.inkpilot.runtime

import inkpilot.agent.BookingClaimGate
import inkpilot.agent.BoundedIntentReplyRenderer
import inkpilot.agent.CustomerServiceDrafts
import inkpilot.agent.DraftCancellation
import inkpilot.agent.DraftDisposition
import inkpilot.agent.DraftEvidenceReader
import inkpilot.agent.DraftOutcome
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftStopReason
import inkpilot.agent.HeldDraftStore
import inkpilot.agent.LocalDraftModel
import inkpilot.inference.LlamaDraftModel
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.readEvidence

internal data class RequestDisposition(
    val created: Boolean = false,
    val needsAttention: Boolean = false,
    val cancelled: Boolean = false,
)

/** One pending source either becomes staff attention or a held draft; it never sends SMS. */
internal fun draftOneRequest(
    repository: StudioRepository, settings: AgentSettings, native: LlamaDraftModel,
    workerToken: String?, request: DraftRequest, cancellation: () -> Boolean,
): RequestDisposition {
    if (!AgentRuntime.BOUNDED_INTENT_QUALIFIED) {
        settings.enabled // Reconcile older ON policy and leave a durable staff task.
        return RequestDisposition(needsAttention = true)
    }
    val model = LocalDraftModel { prompt, stop -> native.generate(prompt, stop) }
    settings.recordStatus("Drafting locally…")
    val store = if (workerToken == null) repository else WorkerDraftStore(repository, workerToken)
    val result = CustomerServiceDrafts(store, repository, model, safetyGate = BookingClaimGate,
        evidenceReader = DraftEvidenceReader { repository.readEvidence(it) }, replyRenderer = BoundedIntentReplyRenderer,
    ).draft(request, DraftCancellation { cancellation() || !repository.isDraftingEnabled() })
    return recordDraftOutcome(result, store, repository, request, settings)
}

private fun recordDraftOutcome(result: DraftOutcome, store: HeldDraftStore, repository: StudioRepository,
                               request: DraftRequest, settings: AgentSettings): RequestDisposition = when (result) {
    is DraftOutcome.Held -> RequestDisposition(created = result.disposition == DraftDisposition.CREATED)
    is DraftOutcome.Failed -> {
        repository.recordDraftAttempt(request.eventId, request.expectedRevision, "Model or source read failed")
        settings.recordStatus("Drafting failed. Check the model and try again.")
        RequestDisposition(needsAttention = true)
    }
    is DraftOutcome.PersistenceUnconfirmed -> {
        settings.recordStatus("Draft storage needs reconciliation. Nothing was sent.")
        requireConfirmedDraftPersistence(store, result.key)
        RequestDisposition()
    }
    is DraftOutcome.Stopped -> {
        settings.recordStatus(if (result.reason == DraftStopReason.UNSAFE_CONTENT)
            "Draft blocked: unverified or unsupported reply. Staff should reply directly."
        else "Draft stopped: " + result.reason.name.lowercase().replace('_', ' ') + ".")
        when {
            result.reason == DraftStopReason.CANCELLED -> RequestDisposition(cancelled = true)
            result.reason !in listOf(DraftStopReason.SOURCE_CHANGED, DraftStopReason.STAFF_PAUSED) -> {
                repository.recordDraftAttempt(request.eventId, request.expectedRevision, result.reason.name)
                RequestDisposition(needsAttention = true)
            }
            else -> RequestDisposition()
        }
    }
}
