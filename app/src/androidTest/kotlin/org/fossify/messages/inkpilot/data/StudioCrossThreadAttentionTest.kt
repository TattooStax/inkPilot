package org.fossify.messages.inkpilot.data

import inkpilot.agent.DraftStoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioCrossThreadAttentionTest : StudioDatabaseTestBase() {
    private val primary = "+15550101111"
    private val alternate = "+15550102222"

    private fun reviewedContact(): String = repository.upsertContact(primary, "Maya").id.also {
        repository.reviewAlias(alternate, "", it)
    }

    @Test fun switchingReviewedAliasThreadsKeepsTheDisplacedLeadVisibleAcrossRestart() {
        val contactId = reviewedContact()
        val first = repository.recordInbound("sms:cross-1", 41, primary, "First thread question", "Maya", 1_000, 3)
        assertTrue(repository.holdIfCurrent(write(first)) is DraftStoreResult.Held)
        val second = repository.recordInbound("sms:cross-2", 42, alternate, "Second thread question", "Maya", 2_000, 3)
        assertEquals(contactId, second.contactId)
        assertEquals(second.eventId, repository.pendingRequests().single().eventId)
        assertEquals(first.eventId, repository.staffAttention().single().eventId)
        assertEquals(41L, repository.staffAttention().single().threadId)
        assertFalse(repository.staffAttention().single().current)
        reopen()
        assertEquals(first.eventId, repository.staffAttention().single().eventId)
        assertTrue(repository.markStaffAttentionHandled(first.eventId, first.expectedRevision))
        assertTrue(repository.staffAttention().isEmpty())
        assertEquals(second.eventId, repository.pendingRequests().single().eventId)
    }

    @Test fun claimedOrStaffHandledThreadIsNotReopenedByAnotherThread() {
        reviewedContact()
        val first = repository.recordInbound("sms:claimed-1", 41, primary, "First thread", "Maya", 1_000, 3)
        val draft = held(first)
        assertTrue(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3) != null)
        repository.recordInbound("sms:claimed-2", 42, alternate, "Second thread", "Maya", 2_000, 3)
        assertTrue(repository.staffAttention().isEmpty())

        val third = repository.recordInbound("sms:handled-1", 42, alternate, "Question in second thread", "Maya", 3_000, 3)
        assertTrue(repository.recordDraftAttempt(third.eventId, third.expectedRevision, "MODEL_FAILED"))
        assertTrue(repository.markStaffAttentionHandled(third.eventId, third.expectedRevision))
        repository.recordInbound("sms:handled-2", 41, primary, "Back to first thread", "Maya", 4_000, 3)
        assertTrue(repository.staffAttention().isEmpty())
    }

    @Test fun lateArrivalFromAnotherThreadGetsOwnTaskWithoutChangingLatestDraft() {
        reviewedContact()
        val latest = repository.recordInbound("sms:current", 41, primary, "Current question", "Maya", 2_000, 3)
        val late = repository.recordInbound("sms:late", 42, alternate, "Late imported question", "Maya", 1_000, 3)
        assertEquals(latest.eventId, repository.pendingRequests().single().eventId)
        assertEquals(late.eventId, repository.staffAttention().single().eventId)
        assertEquals(42L, repository.staffAttention().single().threadId)
        reopen()
        repository.recordInbound("sms:late", 42, alternate, "Late imported question", "Maya", 1_000, 3)
        assertEquals(1, repository.staffAttention().size)
    }

    @Test fun aliasReassignmentKeepsOldOwnerLeadVisibleAcrossRestart() {
        val previous = repository.upsertContact(primary, "Old owner")
        val current = repository.upsertContact(alternate, "New owner")
        val pending = repository.recordInbound("sms:old-owner", 41, primary,
            "Can you help with my tattoo?", "Old owner", 1_000, 3)
        repository.reviewAlias(primary, "", current.id)
        assertEquals(previous.id, pending.contactId)
        assertNull(repository.readSource(pending.eventId))
        val task = repository.staffAttention().single()
        assertEquals(pending.eventId, task.eventId)
        assertEquals(41L, task.threadId)
        assertEquals("IDENTITY_REASSIGNED", task.reason)
        assertFalse(task.current)
        reopen()
        assertEquals(pending.eventId, repository.staffAttention().single().eventId)
        repository.reviewAlias(primary, "", current.id)
        assertEquals(1, repository.staffAttention().size)
    }

    @Test fun aliasReassignmentDoesNotReopenClaimedOrHandledEvent() {
        val old = repository.upsertContact(primary, "Old owner")
        val current = repository.upsertContact(alternate, "New owner")
        val pending = repository.recordInbound("sms:claimed-owner", 41, primary,
            "Question already sent", "Old owner", 1_000, 3)
        val draft = held(pending)
        assertTrue(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3) != null)
        repository.reviewAlias(primary, "", current.id)
        assertTrue(repository.staffAttention().isEmpty())

        val other = repository.upsertContact("+15550103333", "Other owner")
        val handled = repository.recordInbound("sms:handled-owner", 43, other.number,
            "Staff handled", "Other owner", 2_000, 3)
        assertTrue(repository.recordDraftAttempt(handled.eventId, handled.expectedRevision, "MODEL_FAILED"))
        assertTrue(repository.markStaffAttentionHandled(handled.eventId, handled.expectedRevision))
        repository.reviewAlias(other.number, "", current.id)
        assertTrue(repository.staffAttention().isEmpty())
        assertEquals(old.id, pending.contactId)
    }

    @Test fun whitespaceAroundSourceDoesNotInvalidateAReviewedHeldDraft() {
        val inbound = inbound("sms:trim-source")
        repository.saveContactEdit(inbound.contactId, "Review client preference", false, " Staff note ")
        val source = requireNotNull(repository.readSource(inbound.eventId))
        val request = inkpilot.agent.DraftRequest(inbound.eventId, inbound.threadId, inbound.contactId, source.revision)
        assertTrue(repository.holdIfCurrent(write(request)) is DraftStoreResult.Held)
        repository.saveContactEdit(inbound.contactId, "Review client preference", false, " Staff note ")
        assertEquals(source.revision, repository.contact(inbound.contactId)?.revision)
        assertTrue(repository.heldDrafts().single().canReview)
    }
}
