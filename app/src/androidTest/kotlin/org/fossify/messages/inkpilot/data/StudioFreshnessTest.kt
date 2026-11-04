package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftStoreResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioFreshnessTest : StudioDatabaseTestBase() {
    @Test
    fun soulRegenerationRejectsLateWritesAndPreservesOriginalReceipt() {
        val request = inbound()
        val original = held(request)
        repository.setSoul("Revised studio guide")
        val source = checkNotNull(repository.readSource(request.eventId))
        assertTrue(repository.pendingRequests().isEmpty())
        val queued = repository.regenerate(request.eventId, source.revision) as DraftRegenerationOutcome.Held
        repository.setSoul("Another guide revision during generation")
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(write(queued.request)))
        assertTrue(repository.pendingRequests().isEmpty())
        assertEquals(original.body, repository.drafts().single().body)
        val next = checkNotNull(repository.readSource(request.eventId))
        assertTrue(repository.regenerate(request.eventId, next.revision) is DraftRegenerationOutcome.Held)
        assertTrue(repository.holdIfCurrent(write(repository.pendingRequests().single())) is DraftStoreResult.Held)
        assertEquals(2, repository.drafts().size)
    }

    @Test
    fun regenerationRequiresExplicitCurrentRevisionAndRetainsHistory() {
        val request = inbound()
        val original = held(request)
        repository.saveMemory(request.contactId, "Use current memory")
        assertTrue(repository.pendingRequests().isEmpty())
        assertEquals(DraftRegenerationOutcome.Stale, repository.regenerate(request.eventId, request.expectedRevision))
        val current = checkNotNull(repository.readSource(request.eventId))
        val authorized = repository.regenerate(request.eventId, current.revision) as DraftRegenerationOutcome.Held
        reopen()
        assertEquals(authorized.request, repository.pendingRequests().single())
        val result = repository.holdIfCurrent(write(authorized.request, "Updated reply")) as DraftStoreResult.Held
        assertNotEquals(original.draftId, result.receipt.draftId)
        assertEquals(2, repository.drafts().size)
        assertEquals(original.body, repository.drafts().single { it.draftId == original.draftId }.body)
        assertFalse(repository.drafts().single { it.draftId == original.draftId }.canReview)
        assertTrue(repository.pendingRequests().isEmpty())
    }

    @Test
    fun soulChangesAndRetryNeverReplayClaimedEvent() {
        val request = inbound()
        val original = held(request)
        assertNotNull(repository.claimDraftForSend(original.draftId, original.body, original.body, 3))
        repository.setSoul("New guide")
        repository.clearDraftAttempts()
        reopen()
        val revision = checkNotNull(repository.readSource(request.eventId)).revision
        assertEquals(DraftRegenerationOutcome.AlreadyClaimed, repository.regenerate(request.eventId, revision))
        assertTrue(repository.pendingRequests().isEmpty())
        assertEquals(original.draftId, repository.getSendClaim(original.draftId)?.draftId)
    }

    @Test
    fun cancellationRevisionRejectsInferenceAlreadyInFlight() {
        val request = inbound()
        val pending = write(request)
        repository.cancelPending()
        reopen()
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(pending))
    }

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
