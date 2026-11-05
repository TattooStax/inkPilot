package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestWorkerBuilder
import inkpilot.agent.*
import org.fossify.messages.inkpilot.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class LocalDraftWorkerRecoveryTest : StudioDatabaseTestBase() {
    private fun worker(workId: UUID = UUID.randomUUID(), action: (LocalDraftWorker) -> Unit): LocalDraftWorker =
        TestWorkerBuilder<LocalDraftWorker>(context, Executor { it.run() }).setId(workId).setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : LocalDraftWorker(appContext, workerParameters) {
                        override fun openRepository() = StudioRepository(context, databaseName)
                        // Exercises worker cancellation/persistence with synthetic writes, never production inference.
                        override fun qualifiedToDraft() = true
                        override fun processDrafts() = action(this)
                    }
            },
        ).build()

    @Test
    fun stoppedWorkerCannotHoldButRestartRetainsAuthorizationAndOtherReadyDrafts() {
        val request = inbound()
        val original = held(request)
        repository.saveMemory(request.contactId, "Current facts")
        val current = checkNotNull(repository.readSource(request.eventId))
        val authorized = repository.regenerate(request.eventId, current.revision) as DraftRegenerationOutcome.Held
        val other = held(inbound("sms:other", "+15557654321"))
        val workId = UUID.randomUUID()
        val interrupted = worker(workId) { running ->
            val proposal = write(authorized.request)
            running.onStopped() // WorkManager's actual stop hook, during an in-flight attempt.
            assertEquals(DraftStoreResult.SourceChanged, WorkerDraftStore(repository, running.cancellationToken).holdIfCurrent(proposal))
        }
        interrupted.doWork()
        reopen()
        assertEquals(authorized.request, repository.pendingRequests().single())
        assertEquals(current.revision, repository.readSource(request.eventId)?.revision)
        assertTrue(repository.drafts().single { it.draftId == other.draftId }.canReview)
        val restarted = worker(workId) { running ->
            val outcome = CustomerServiceDrafts(
                WorkerDraftStore(repository, running.cancellationToken), repository, LocalDraftModel { _, _ -> "Restarted answer" },
            ).draft(repository.pendingRequests().single())
            assertTrue(outcome is DraftOutcome.Held)
        }
        assertEquals(interrupted.id, restarted.id)
        assertNotEquals(interrupted.cancellationToken, restarted.cancellationToken)
        assertEquals(ListenableWorker.Result.success(), restarted.doWork())
        assertTrue(repository.pendingRequests().isEmpty())
        assertEquals(original.body, repository.drafts().single { it.draftId == original.draftId }.body)
        assertEquals(3, repository.drafts().size)
    }

    @Test
    fun transientUnconfirmedHoldRetriesAndRestartCompletesPendingGeneration() {
        val request = inbound()
        val first = worker { running ->
            val scoped = WorkerDraftStore(repository, running.cancellationToken)
            val failing = object : HeldDraftStore by scoped {
                override fun holdIfCurrent(write: HeldDraftWrite): DraftStoreResult = throw IllegalStateException("Synthetic pre-commit failure")
            }
            val outcome = CustomerServiceDrafts(failing, repository, LocalDraftModel { _, _ -> "Synthetic answer" }).draft(request)
            assertTrue(outcome is DraftOutcome.PersistenceUnconfirmed)
            requireConfirmedDraftPersistence(failing, (outcome as DraftOutcome.PersistenceUnconfirmed).key)
        }
        assertEquals(ListenableWorker.Result.retry(), first.doWork())
        reopen()
        assertEquals(request, repository.pendingRequests().single())
        assertTrue(repository.heldDrafts().isEmpty())
        val restarted = worker { running ->
            assertTrue(CustomerServiceDrafts(
                WorkerDraftStore(repository, running.cancellationToken), repository, LocalDraftModel { _, _ -> "Recovered answer" },
            ).draft(repository.pendingRequests().single()) is DraftOutcome.Held)
        }
        assertEquals(ListenableWorker.Result.success(), restarted.doWork())
        assertTrue(repository.pendingRequests().isEmpty())
        assertEquals(1, repository.heldDrafts().size)
    }

    @Test
    fun exceptionAfterCommittedHoldReconcilesReceiptWithoutDuplicateGeneration() {
        val request = inbound()
        val task = worker { running ->
            val scoped = WorkerDraftStore(repository, running.cancellationToken)
            val ambiguous = object : HeldDraftStore by scoped {
                override fun holdIfCurrent(write: HeldDraftWrite): DraftStoreResult {
                    scoped.holdIfCurrent(write)
                    throw IllegalStateException("Synthetic post-commit failure")
                }
            }
            val outcome = CustomerServiceDrafts(ambiguous, repository, LocalDraftModel { _, _ -> "Committed answer" }).draft(request)
            assertTrue(outcome is DraftOutcome.PersistenceUnconfirmed)
            requireConfirmedDraftPersistence(ambiguous, (outcome as DraftOutcome.PersistenceUnconfirmed).key)
        }
        assertEquals(ListenableWorker.Result.success(), task.doWork())
        reopen()
        assertEquals(1, repository.heldDrafts().size)
        assertTrue(repository.pendingRequests().isEmpty())
    }
}
