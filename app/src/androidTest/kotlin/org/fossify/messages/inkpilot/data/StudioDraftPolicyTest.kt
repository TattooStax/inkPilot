package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftDisposition
import inkpilot.agent.DraftRendererPolicy
import inkpilot.agent.DraftStoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioDraftPolicyTest : StudioDatabaseTestBase() {
    @Test fun boundedReceiptSurvivesRestartAndOnlyItsClaimIsAccepted() {
        val request = inbound()
        val expected = write(request, "Thanks for reaching out. What size are you thinking?")
        val first = repository.holdIfCurrent(expected) as DraftStoreResult.Held
        assertEquals(DraftRendererPolicy.BOUNDED_INTENT_V1, first.receipt.rendererPolicy)
        reopen()
        val existing = repository.findHeldDraft(expected.key)
        assertEquals(first.receipt, existing)
        val replay = repository.holdIfCurrent(expected) as DraftStoreResult.Held
        assertEquals(DraftDisposition.ALREADY_HELD, replay.disposition)
        assertEquals(first.receipt, replay.receipt)
        assertTrue(repository.draft(first.receipt.draftId)!!.canReview)
        assertNotNull(repository.claimDraftForSend(first.receipt.draftId, expected.body, expected.body, 3))
    }
}
