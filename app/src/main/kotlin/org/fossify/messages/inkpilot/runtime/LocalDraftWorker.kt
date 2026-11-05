package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.fossify.messages.inkpilot.data.StudioRepository
import java.util.UUID

open class LocalDraftWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    // WorkManager may retry the same work ID. Every Worker instance gets a new epoch.
    internal val cancellationToken: String = UUID.randomUUID().toString()
    protected open fun openRepository() = StudioRepository(applicationContext)
    protected open fun processDrafts() {
        AgentRuntime.processPendingForWorker(applicationContext, cancellationToken) { isStopped }
    }
    protected open fun qualifiedToDraft(): Boolean = AgentSettings(applicationContext).enabled &&
        AgentRuntime.BOUNDED_INTENT_QUALIFIED

    override fun onStopped() {
        try {
            openRepository().use { it.cancelWorker(cancellationToken) }
        } finally {
            super.onStopped()
        }
    }

    override fun doWork(): Result = try {
        if (qualifiedToDraft()) processDrafts()
        Result.success()
    } catch (_: Exception) {
        AgentSettings(applicationContext).recordStatus("Drafting will retry. Nothing was sent.")
        Result.retry()
    }
}
