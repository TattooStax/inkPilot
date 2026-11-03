package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioRecoveryTest : StudioDatabaseTestBase() {
    @Test
    fun providerHistoryUpdateRemainsPendingAcrossProcessRestartUntilExactAcknowledgement() {
        val draft = held()
        repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3)
        repository.attachProviderId(draft.draftId, 123)
        assertFalse(repository.markProviderSynced(draft.draftId, 123))
        assertTrue(repository.pendingProviderUpdates().isEmpty())
        repository.markSendResult(draft.draftId, SendStatus.SENT)
        reopen()
        assertEquals(draft.draftId, repository.pendingProviderUpdates().single().draftId)
        assertFalse(repository.markProviderSynced(draft.draftId, 999))
        assertTrue(repository.markProviderSynced(draft.draftId, 123))
        assertTrue(repository.markProviderSynced(draft.draftId, 123))
        reopen()
        assertTrue(repository.pendingProviderUpdates().isEmpty())
        assertEquals(SendStatus.SENT, repository.getSendClaim(draft.draftId)?.status)
    }

    @Test
    fun unknownAttemptNeverEntersHistoryQueueAsSentOrFailed() {
        val draft = held()
        repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3)
        repository.attachProviderId(draft.draftId, 123)
        repository.markSendResult(draft.draftId, SendStatus.UNKNOWN)
        assertTrue(repository.pendingProviderUpdates().isEmpty())
        assertFalse(repository.markProviderSynced(draft.draftId, 123))
    }

    @Test
    fun failedRevisionDoesNotStarveLaterContactsAndExplicitRetryReleasesIt() {
        val first = inbound()
        val second = inbound("sms:2", "+15559876543")
        assertTrue(repository.recordDraftAttempt(first.eventId, first.expectedRevision, "CONTEXT_LIMIT"))
        reopen()
        assertEquals(second, repository.pendingRequests().single())
        repository.clearDraftAttempts()
        assertEquals(2, repository.pendingRequests().size)
    }

    @Test
    fun revisionChangeReleasesOldFailureWithoutRecordingFailureAgainstNewState() {
        val first = inbound()
        assertTrue(repository.recordDraftAttempt(first.eventId, first.expectedRevision, "MODEL"))
        repository.saveMemory(first.contactId, "Updated facts")
        assertFalse(repository.recordDraftAttempt(first.eventId, first.expectedRevision, "Late failure"))
        assertTrue(repository.pendingRequests().single().expectedRevision > first.expectedRevision)
    }

    @Test
    fun heldOrClaimedDraftCannotBeMarkedAsFailedGeneration() {
        val request = inbound()
        val draft = held(request)
        assertFalse(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "Late failure"))
        repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3)
        assertFalse(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "Late failure"))
        repository.clearDraftAttempts()
        assertTrue(repository.pendingRequests().isEmpty())
    }
}
