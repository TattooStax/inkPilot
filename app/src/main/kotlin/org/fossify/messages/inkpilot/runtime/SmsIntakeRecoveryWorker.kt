package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Continuations persist in WorkManager even when inference is disabled. Never submits SMS. */
open class SmsIntakeRecoveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    protected open fun recoverInbox() = SmsIntakeRecovery.recover(applicationContext)
    protected open fun scheduleDrafts() = AgentRuntime.runPending(applicationContext)

    override fun doWork(): Result = try {
        recoverInbox()
        scheduleDrafts()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
