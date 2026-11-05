package org.fossify.messages.inkpilot.runtime

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import inkpilot.agent.CustomerServiceDrafts
import inkpilot.agent.BookingClaimGate
import inkpilot.agent.BoundedIntentReplyRenderer
import inkpilot.agent.DraftCancellation
import inkpilot.agent.DraftDisposition
import inkpilot.agent.DraftEvidenceReader
import inkpilot.agent.DraftReplyRenderer
import inkpilot.agent.DraftOutcome
import inkpilot.agent.DraftRequest
import inkpilot.agent.HeldDraftStore
import inkpilot.agent.DraftStopReason
import inkpilot.agent.LocalDraftModel
import inkpilot.inference.LlamaDraftModel
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.readEvidence
import org.fossify.messages.inkpilot.data.blockInactiveLeadScope

object AgentRuntime {
    // The bounded route stays off until blind model and device qualification pass.
    internal const val BOUNDED_INTENT_QUALIFIED = false
    internal val inferenceLock = Any()

    fun importModel(context: Context, uri: Uri): String = ModelFiles.import(context.applicationContext, uri)

    fun retryPending(context: Context) {
        StudioRepository(context).use { it.clearDraftAttempts() }
        runPending(context)
    }

    fun regenerate(context: Context, eventId: String, expectedRevision: Long): org.fossify.messages.inkpilot.data.DraftRegenerationOutcome {
        val result = StudioRepository(context).use {
            it.regenerate(eventId, expectedRevision, allowStoredLeadReview = BOUNDED_INTENT_QUALIFIED)
        }
        if (result is org.fossify.messages.inkpilot.data.DraftRegenerationOutcome.Held) runPending(context)
        return result
    }

    fun reconcileHistory(context: Context) = SmsProviderHistory.reconcile(context)

    fun runPending(context: Context) {
        runCatching { SmsIntakeRecovery.recover(context) }
            .onFailure {
                SmsIntakeRecovery.enqueueContinuation(context)
                AgentSettings(context).recordStatus("Inbox recovery will retry. Existing drafts remain available.")
            }
        if (!AgentSettings(context).enabled) return
        AgentSettings(context).recordStatus("Drafting queued locally. Nothing was sent.")
        WorkManager.getInstance(context).enqueueUniqueWork(
            "inkpilot-local-drafts", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<LocalDraftWorker>().build(),
        )
    }

    /** The worker and instrumentation use the same production path, without a fake inference adapter. */
    fun processPending(context: Context, cancellation: () -> Boolean = { false }): Int =
        processPendingForWorker(context, null, cancellation)

    internal fun processPendingForWorker(context: Context, workerToken: String?, cancellation: () -> Boolean): Int = synchronized(inferenceLock) {
        val settings = AgentSettings(context)
        StudioRepository(context).use { repository ->
            if (!repository.isDraftingEnabled()) return@synchronized 0
            if (!org.fossify.messages.helpers.SmsRole.isHeld(context)) {
                settings.recordStatus("Make inkPilot the default SMS app before drafting.")
                return@synchronized 0
            }
            val weights = ModelFiles.active(context)
            if (!weights.isFile) {
                settings.recordStatus("Import a local GGUF model first.")
                return@synchronized 0
            }
            val batch = draftPendingRequests(repository, settings, LlamaDraftModel(weights), workerToken, cancellation)
            val created = batch.created
            val needsAttention = batch.needsAttention
            if (created > 0) {
                settings.recordStatus(if (needsAttention) "Drafts are ready for review. Some messages could not be drafted; check the model and memory, then retry."
                    else "$created draft(s) ready for staff review.")
                DraftNotifications.show(context, created)
            }
            if (!cancellation() && repository.pendingRequests(limit = 1).isNotEmpty()) runPending(context)
            created
        }
    }

    private data class DraftBatchState(val created: Int, val needsAttention: Boolean)

    private fun draftPendingRequests(repository: StudioRepository, settings: AgentSettings, native: LlamaDraftModel,
                                     workerToken: String?, cancellation: () -> Boolean): DraftBatchState {
        var created = 0
        var needsAttention = false
        for (request in repository.pendingRequests(limit = 3)) {
            if (cancellation() || !repository.isDraftingEnabled()) break
            if (!BOUNDED_INTENT_QUALIFIED && repository.blockInactiveLeadScope(request)) {
                settings.recordStatus("Lead review inactive for local drafts. Staff should reply directly.")
                needsAttention = true
                continue
            }
            val model = LocalDraftModel { prompt, stop ->
                val instructions = if (BOUNDED_INTENT_QUALIFIED) prompt.instructions
                    else prompt.instructions + "\nStudio guide:\n" + repository.soul()
                native.generate(prompt.copy(instructions = instructions), stop)
            }
            settings.recordStatus("Drafting locally…")
            val store = if (workerToken == null) repository else WorkerDraftStore(repository, workerToken)
            val evidence = if (BOUNDED_INTENT_QUALIFIED) DraftEvidenceReader { repository.readEvidence(it) } else null
            val renderer = if (BOUNDED_INTENT_QUALIFIED) BoundedIntentReplyRenderer else DraftReplyRenderer.RAW
            val result = CustomerServiceDrafts(store, repository, model, safetyGate = BookingClaimGate,
                evidenceReader = evidence, replyRenderer = renderer,
            ).draft(request, DraftCancellation { cancellation() || !repository.isDraftingEnabled() })
            val disposition = recordDraftOutcome(result, store, repository, request, settings)
            if (disposition.created) created++
            if (disposition.needsAttention) needsAttention = true
            if (disposition.cancelled) break
        }
        return DraftBatchState(created, needsAttention)
    }

    private data class RequestDisposition(
        val created: Boolean = false,
        val needsAttention: Boolean = false,
        val cancelled: Boolean = false,
    )

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

    fun approveAndSend(context: Context, draftId: String, editedBody: String, subscriptionId: Int): String =
        CheckedSmsSender(context.applicationContext).approveAndSend(draftId, editedBody, subscriptionId)
}
