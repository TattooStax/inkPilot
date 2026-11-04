package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class LocalDraftWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun onStopped() {
        // Durable barrier closes the race between the last cancellation check and hold commit.
        AgentRuntime.cancelPending(applicationContext)
        super.onStopped()
    }

    override fun doWork(): Result = try {
        AgentRuntime.processPending(applicationContext) { isStopped }
        Result.success()
    } catch (_: Exception) {
        AgentSettings(applicationContext).recordStatus("Drafting could not finish. Open Agent to retry.")
        Result.retry()
    }
}
