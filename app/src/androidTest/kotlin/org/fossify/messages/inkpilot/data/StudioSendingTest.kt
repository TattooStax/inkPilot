package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioSendingTest : StudioDatabaseTestBase() {
    @Test
    fun editedStaffProposalIsImmutableAndClaimCannotBeRepeated() {
        val draft = held()
        val claim = requireNotNull(repository.claimDraftForSend(draft.draftId, draft.body, "Staff edited reply", 3))
        assertEquals("Staff edited reply", claim.body)
        assertEquals(SendStatus.SENDING, claim.status)
        assertEquals(draft.body, repository.findHeldDraft(DraftKey(draft.eventId))?.body)
        assertNull(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3))
        reopen()
        assertEquals(claim, repository.getSendClaim(draft.draftId))
        assertTrue(repository.heldDrafts().isEmpty())
        assertEquals("SENDING", repository.drafts().single().status)
        assertEquals("Staff edited reply", repository.drafts().single().sendBody)
    }

    @Test
    fun mismatchedDisplayedBodyAndPausedContactCannotBeApproved() {
        val draft = held()
        assertNull(repository.claimDraftForSend(draft.draftId, "Different displayed body", draft.body, 3))
        repository.setPaused(draft.contactId, true)
        assertNull(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3))
        assertNull(repository.getSendClaim(draft.draftId))
    }

    @Test
    fun providerIdentityAndTerminalOutcomeCannotBeChangedByReplay() {
        val draft = held()
        repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3)
        assertTrue(repository.attachProviderId(draft.draftId, 123))
        assertTrue(repository.attachProviderId(draft.draftId, 123))
        assertFalse(repository.attachProviderId(draft.draftId, 999))
        assertTrue(repository.markSendResult(draft.draftId, SendStatus.SENT, 123))
        assertTrue(repository.markSendResult(draft.draftId, SendStatus.SENT, 123))
        assertFalse(repository.markSendResult(draft.draftId, SendStatus.FAILED, 123, "late failure"))
        assertFalse(repository.markSendResult(draft.draftId, SendStatus.SENT, 999))
        reopen()
        assertEquals(SendStatus.SENT, repository.getSendClaim(draft.draftId)?.status)
        assertEquals(123L, repository.getSendClaim(draft.draftId)?.providerId)
    }

    @Test
    fun ambiguousAttemptStaysRecordedAndIsNeverReclaimed() {
        val draft = held()
        repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3)
        assertTrue(repository.markSendResult(draft.draftId, SendStatus.UNKNOWN, error = "No definitive carrier result"))
        reopen()
        assertEquals(SendStatus.UNKNOWN, repository.getSendClaim(draft.draftId)?.status)
        assertNull(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3))
        assertTrue(repository.pendingRequests().isEmpty())
    }

    @Test
    fun invalidRecipientSelectionAndBodyCannotCreateClaim() {
        val draft = held()
        assertThrows(IllegalArgumentException::class.java) { repository.claimDraftForSend(draft.draftId, draft.body, "reply", -1) }
        assertThrows(IllegalArgumentException::class.java) { repository.claimDraftForSend(draft.draftId, draft.body, " ", 3) }
        assertThrows(IllegalArgumentException::class.java) { repository.claimDraftForSend(draft.draftId, draft.body, "x".repeat(801), 3) }
        assertNull(repository.getSendClaim(draft.draftId))
        assertTrue(repository.heldDrafts().single().canReview)
    }
}
