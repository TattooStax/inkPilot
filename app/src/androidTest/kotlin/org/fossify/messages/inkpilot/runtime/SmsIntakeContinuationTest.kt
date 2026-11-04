package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Inherits the emulator-only provider guard. Never points a test worker at the app's Studio DB. */
@RunWith(AndroidJUnit4::class)
class SmsIntakeContinuationTest : SmsIntakeRecoveryTestBase() {
    @Test
    fun budgetExhaustionEnqueuesAndExecutesContinuationWithReopenedRepository() {
        var latest = afterId
        repeat(405) { latest = insertInbox("+12025550991", "Synthetic continuation $it") }
        repository.advanceIntakeCheckpoint(afterId)
        // Every actual continuation worker opens a new connection, simulating lost process state.
        val workerStarts = AtomicInteger()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != SmsIntakeRecoveryWorker::class.java.name) return null
                return object : SmsIntakeRecoveryWorker(appContext, workerParameters) {
                    override fun recoverInbox() {
                        workerStarts.incrementAndGet()
                        openFixtureRepository().use { SmsIntakeRecovery.recoverAndSchedule(context, it, since, maxPages = 1) }
                    }
                    override fun scheduleDrafts() = Unit
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setWorkerFactory(factory).build(),
        )
        val manager = WorkManager.getInstance(context)
        try {
            SmsIntakeRecovery.recoverAndSchedule(context, repository, since, maxPages = 1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var allFinished = false
            while (System.nanoTime() < deadline) {
                val info = manager.getWorkInfosByTag(SmsIntakeRecovery.CONTINUATION_TAG).get(5, TimeUnit.SECONDS)
                if (repository.intakeCheckpoint() == latest && workerStarts.get() >= 2 &&
                    info.isNotEmpty() && info.all { it.state == WorkInfo.State.SUCCEEDED }) {
                    allFinished = true
                    break
                }
                Thread.sleep(25)
            }
            assertTrue("Queued continuation workers must finish without another manual recovery wake", allFinished)
            assertTrue(workerStarts.get() >= 2)
            reopenRepository()
            assertEquals(latest, repository.intakeCheckpoint())
            assertEquals("sms:$latest", repository.contacts().single().latestEventId)
            assertTrue(repository.heldDrafts().isEmpty())
            assertEquals("Synthetic continuation 404", nativeBody(latest))
        } finally {
            manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.closeWorkDatabase()
            WorkManagerImpl.setDelegate(null)
        }
    }
}
