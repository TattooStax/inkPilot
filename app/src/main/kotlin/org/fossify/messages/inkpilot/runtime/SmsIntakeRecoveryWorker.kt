package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Continuations persist in WorkManager even when inference is disabled. Never submits SMS. */
class SmsIntakeRecoveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        SmsIntakeRecovery.recover(applicationContext)
        AgentRuntime.runPending(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
