package org.fossify.messages.inkpilot.runtime

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import inkpilot.inference.LlamaDraftModel
import org.fossify.messages.inkpilot.data.StudioRepository

object AgentRuntime {
    // The bounded route stays off until blind model and device qualification pass.
    internal const val BOUNDED_INTENT_QUALIFIED = false
    internal val inferenceLock = Any()

    fun importModel(context: Context, uri: Uri): String = ModelFiles.import(context.applicationContext, uri)

    fun retryPending(context: Context) {
        if (!AgentSettings(context).enabled) return
        StudioRepository(context).use { it.clearDraftAttempts() }
        runPending(context)
    }

    fun regenerate(context: Context, eventId: String, expectedRevision: Long): org.fossify.messages.inkpilot.data.DraftRegenerationOutcome {
        if (!AgentSettings(context).enabled) {
            return org.fossify.messages.inkpilot.data.DraftRegenerationOutcome.ReviewScopeUnavailable
        }
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
        if (!settings.enabled || !BOUNDED_INTENT_QUALIFIED) return@synchronized 0
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
            val disposition = draftOneRequest(repository, settings, native, workerToken, request, cancellation)
            if (disposition.created) created++
            if (disposition.needsAttention) needsAttention = true
            if (disposition.cancelled) break
        }
        return DraftBatchState(created, needsAttention)
    }

    fun approveAndSend(context: Context, draftId: String, editedBody: String, subscriptionId: Int): String =
        CheckedSmsSender(context.applicationContext).approveAndSend(draftId, editedBody, subscriptionId)
}
