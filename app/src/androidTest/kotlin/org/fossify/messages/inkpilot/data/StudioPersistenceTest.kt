package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftDisposition
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftStoreResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StudioPersistenceTest : StudioDatabaseTestBase() {
    @Test
    fun committedReceiptAndMemorySurviveReopenAndReplay() {
        val request = inbound()
        repository.saveMemory(request.contactId, "Prefers black and grey.")
        val current = repository.pendingRequests().single()
        val draft = held(current)
        reopen()
        assertEquals("Prefers black and grey.", repository.read(request.contactId).single().text)
        assertEquals(draft.draftId, repository.findHeldDraft(DraftKey(request.eventId))?.draftId)
        val replay = repository.holdIfCurrent(write(current)) as DraftStoreResult.Held
        assertEquals(DraftDisposition.ALREADY_HELD, replay.disposition)
        assertEquals(draft.draftId, replay.receipt.draftId)
        assertTrue(repository.pendingRequests().isEmpty())
    }

    @Test
    fun dismissingRetainsReplayTombstoneAcrossReopen() {
        val request = inbound()
        val draft = held(request)
        assertTrue(repository.dismissDraft(draft.draftId))
        assertFalse(repository.dismissDraft(draft.draftId))
        reopen()
        assertTrue(repository.heldDrafts().isEmpty())
        assertTrue(repository.pendingRequests().isEmpty())
        assertNotNull(repository.findHeldDraft(DraftKey(request.eventId)))
        assertEquals(DraftDisposition.ALREADY_HELD, (repository.holdIfCurrent(write(request)) as DraftStoreResult.Held).disposition)
        assertTrue(repository.heldDrafts().isEmpty())
    }

    @Test
    fun concurrentRepositoriesCommitExactlyOneDraft() {
        val proposal = write(inbound())
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<DraftStoreResult> {
                    StudioRepository(context, databaseName).use { connection ->
                        connection.contacts()
                        barrier.await(10, TimeUnit.SECONDS)
                        connection.holdIfCurrent(proposal)
                    }
                }
            }
            val results = futures.map { it.get(15, TimeUnit.SECONDS) as DraftStoreResult.Held }
            assertEquals(1, results.count { it.disposition == DraftDisposition.CREATED })
            assertEquals(1, results.count { it.disposition == DraftDisposition.ALREADY_HELD })
            assertEquals(1, results.map { it.receipt.draftId }.distinct().size)
            assertEquals(1, repository.heldDrafts().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentFirstOpenDoesNotRaceSchemaCreation() {
        val name = "$databaseName-initial"
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index ->
                executor.submit<String> {
                    StudioRepository(context, name).use { connection ->
                        barrier.await(10, TimeUnit.SECONDS)
                        connection.upsertContact("+1555000000$index", "Contact $index").id
                    }
                }
            }
            assertEquals(2, futures.map { it.get(15, TimeUnit.SECONDS) }.distinct().size)
            StudioRepository(context, name).use { assertEquals(2, it.contacts().size) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            context.deleteDatabase(name)
        }
    }

    @Test
    fun duplicateEventDoesNotBumpRevisionOrPermitChangedScope() {
        val first = inbound()
        assertEquals(first, inbound())
        assertEquals(first.expectedRevision, repository.contact(first.contactId)?.revision)
        assertThrows(IllegalArgumentException::class.java) { inbound(number = "+15559876543") }
        assertEquals(1, repository.contacts().size)
        assertEquals(1, repository.pendingRequests().size)
    }

    @Test
    fun formattedNumberReusesContactWithoutGuessingCountryCode() {
        val original = repository.upsertContact("+1 (555) 123-4567", "Maya")
        val same = repository.upsertContact("+15551234567", "Maya T.")
        assertEquals(original.id, same.id)
        assertEquals("Maya T.", same.displayName)
        assertTrue(same.revision > original.revision)
        val local = repository.upsertContact("5551234567", "Different explicit routing")
        assertNotEquals(original.id, local.id)
    }
}
