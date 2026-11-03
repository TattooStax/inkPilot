package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioSendPartsTest : StudioDatabaseTestBase() {
    private fun sending(parts: Int): String {
        val draft = held()
        repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3)
        assertTrue(repository.prepareSendParts(draft.draftId, parts))
        return draft.draftId
    }

    @Test
    fun outOfOrderSuccessWaitsForEveryPartAndSurvivesReopen() {
        val id = sending(3)
        assertFalse(repository.prepareSendParts(id, 3))
        assertEquals(SendStatus.SENDING, repository.recordSendPart(id, 2, true))
        assertEquals(SendStatus.SENDING, repository.recordSendPart(id, 2, true))
        reopen()
        assertEquals(SendStatus.SENDING, repository.recordSendPart(id, 0, true))
        assertEquals(SendStatus.SENT, repository.recordSendPart(id, 1, true))
        assertEquals(SendStatus.SENT, repository.recordSendPart(id, 1, false, "Contradictory late result"))
        assertEquals(SendStatus.SENT, repository.getSendClaim(id)?.status)
    }

    @Test
    fun anyFailedPartPreventsLaterSuccessFromClaimingWholeMessageSent() {
        val id = sending(2)
        assertEquals(SendStatus.FAILED, repository.recordSendPart(id, 1, false, "RADIO_OFF"))
        reopen()
        assertEquals(SendStatus.FAILED, repository.recordSendPart(id, 0, true))
        assertEquals("RADIO_OFF", repository.getSendClaim(id)?.error)
    }

    @Test
    fun conflictingDuplicatePartCannotReplaceItsFirstOutcome() {
        val id = sending(2)
        assertEquals(SendStatus.SENDING, repository.recordSendPart(id, 0, true))
        assertEquals(SendStatus.SENDING, repository.recordSendPart(id, 0, false, "Duplicate"))
        assertEquals(SendStatus.SENT, repository.recordSendPart(id, 1, true))
    }

    @Test
    fun unknownPartCannotCompleteOrMutateSend() {
        val id = sending(1)
        assertNull(repository.recordSendPart(id, 1, true))
        assertEquals(SendStatus.SENDING, repository.getSendClaim(id)?.status)
        assertEquals(SendStatus.SENT, repository.recordSendPart(id, 0, true))
    }
}
