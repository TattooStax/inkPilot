package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftStoreResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioFreshnessTest : StudioDatabaseTestBase() {
    @Test
    fun memoryEditInvalidatesPendingWriteAndHeldReview() {
        val request = inbound()
        val proposal = write(request)
        val draft = held(request)
        repository.saveMemory(request.contactId, "Do not offer evenings.")
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(proposal))
        assertFalse(repository.heldDrafts().single().canReview)
        assertNull(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3))
    }

    @Test
    fun pauseAndResumeCannotResurrectOldInference() {
        val request = inbound()
        val proposal = write(request)
        repository.setPaused(request.contactId, true)
        assertEquals(DraftStoreResult.StaffPaused, repository.holdIfCurrent(proposal))
        assertTrue(repository.pendingRequests().isEmpty())
        repository.setPaused(request.contactId, false)
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(proposal))
        assertTrue(repository.pendingRequests().single().expectedRevision > request.expectedRevision)
    }

    @Test
    fun globalPauseAndSoulChangesAreDurableRevisionBarriers() {
        val request = inbound()
        val proposal = write(request)
        repository.setDraftingEnabled(false)
        assertEquals(DraftStoreResult.StaffPaused, repository.holdIfCurrent(proposal))
        assertTrue(repository.pendingRequests().isEmpty())
        reopen()
        assertFalse(repository.isDraftingEnabled())
        repository.setDraftingEnabled(true)
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(proposal))
        val next = repository.pendingRequests().single()
        repository.setSoul("Never promise an appointment without staff review.")
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(write(next)))
        reopen()
        assertEquals("Never promise an appointment without staff review.", repository.soul())
    }

    @Test
    fun newerMessageWinsAndOlderArrivalDoesNotReplaceIt() {
        val old = inbound()
        val oldWrite = write(old)
        val latest = inbound("sms:2", receivedAt = 2_000)
        inbound("sms:late", receivedAt = 500)
        assertNull(repository.readSource(old.eventId))
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(oldWrite))
        assertEquals(latest, repository.pendingRequests().single())
    }

    @Test
    fun oneContactsMemoryAndScopeNeverCrossIntoAnother() {
        val first = inbound()
        val second = inbound("sms:2", "+15559876543")
        repository.saveMemory(first.contactId, "Private note for first customer")
        assertTrue(repository.read(second.contactId).isEmpty())
        val proposal = write(second).copy(contactId = first.contactId)
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(proposal))
        assertTrue(repository.heldDrafts().isEmpty())
    }

    @Test
    fun oversizedMemoryIsRejectedWithoutChangingRevision() {
        val request = inbound()
        assertThrows(IllegalArgumentException::class.java) { repository.saveMemory(request.contactId, "x".repeat(8_001)) }
        assertEquals(request.expectedRevision, repository.contact(request.contactId)?.revision)
        assertTrue(repository.read(request.contactId).isEmpty())
    }
}
