package org.fossify.messages.inkpilot.runtime

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import inkpilot.agent.CustomerServiceDrafts
import inkpilot.agent.DraftCancellation
import inkpilot.agent.DraftDisposition
import inkpilot.agent.DraftOutcome
import inkpilot.agent.DraftStopReason
import inkpilot.agent.LocalDraftModel
import inkpilot.inference.LlamaDraftModel
import org.fossify.messages.inkpilot.data.StudioRepository

object AgentRuntime {
    internal val inferenceLock = Any()

    fun importModel(context: Context, uri: Uri): String = ModelFiles.import(context.applicationContext, uri)

    fun retryPending(context: Context) {
        StudioRepository(context).use { it.clearDraftAttempts() }
        runPending(context)
    }

    fun reconcileHistory(context: Context) = SmsProviderHistory.reconcile(context)

    fun runPending(context: Context) {
        if (!AgentSettings(context).enabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "inkpilot-local-drafts", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<LocalDraftWorker>().build(),
        )
    }

    /** The worker and instrumentation use the same production path, without a fake inference adapter. */
    fun processPending(context: Context, cancellation: () -> Boolean = { false }): Int = synchronized(inferenceLock) {
        val settings = AgentSettings(context)
        StudioRepository(context).use { repository ->
            if (!repository.isDraftingEnabled()) return@synchronized 0
            if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) {
                settings.recordStatus("Make inkPilot the default SMS app before drafting.")
                return@synchronized 0
            }
            val weights = ModelFiles.active(context)
            if (!weights.isFile) {
                settings.recordStatus("Import a local GGUF model first.")
                return@synchronized 0
            }
            var created = 0
            val native = LlamaDraftModel(weights)
            for (request in repository.pendingRequests(limit = 3)) {
                if (cancellation() || !repository.isDraftingEnabled()) break
                val model = LocalDraftModel { prompt, stop ->
                    native.generate(prompt.copy(instructions = prompt.instructions + "\nStudio guide:\n" + repository.soul()), stop)
                }
                settings.recordStatus("Drafting locally…")
                val result = CustomerServiceDrafts(repository, repository, model).draft(
                    request, DraftCancellation { cancellation() || !repository.isDraftingEnabled() },
                )
                when (result) {
                    is DraftOutcome.Held -> if (result.disposition == DraftDisposition.CREATED) created++
                    is DraftOutcome.Failed -> {
                        repository.recordDraftAttempt(request.eventId, request.expectedRevision, "Model or source read failed")
                        settings.recordStatus("Drafting failed. Check the model and try again.")
                        return@synchronized created
                    }
                    is DraftOutcome.PersistenceUnconfirmed -> {
                        settings.recordStatus("Draft storage needs reconciliation. Nothing was sent.")
                        return@synchronized created
                    }
                    is DraftOutcome.Stopped -> {
                        settings.recordStatus("Draft stopped: ${result.reason.name.lowercase().replace('_', ' ')}.")
                        if (result.reason == DraftStopReason.CANCELLED) break
                        if (result.reason !in listOf(DraftStopReason.SOURCE_CHANGED, DraftStopReason.STAFF_PAUSED)) {
                            repository.recordDraftAttempt(request.eventId, request.expectedRevision, result.reason.name)
                        }
                    }
                }
            }
            if (created > 0) {
                settings.recordStatus("$created draft(s) ready for staff review.")
                DraftNotifications.show(context, created)
            }
            if (!cancellation() && repository.pendingRequests(limit = 1).isNotEmpty()) runPending(context)
            created
        }
    }

    fun approveAndSend(context: Context, draftId: String, editedBody: String, subscriptionId: Int): String =
        CheckedSmsSender(context.applicationContext).approveAndSend(draftId, editedBody, subscriptionId)
}
