package inkpilot.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftGuardTest {
    @Test
    fun `source contact mismatch stops before reading memory or using the model`() {
        val fixture = DraftFixture()
        fixture.store.source = fixture.initialSource.copy(contactId = "other")
        assertStopped(fixture, DraftStopReason.SCOPE_MISMATCH)
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `cross contact memory fails closed`() {
        val fixture = DraftFixture()
        fixture.notes = fixture.notes + ContactMemory("other", "Private information")
        assertStopped(fixture, DraftStopReason.SCOPE_MISMATCH)
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `cross contact receipt cannot be reused`() {
        val fixture = DraftFixture()
        fixture.store.receipts[DraftKey("event-1")] = HeldDraftReceipt(
            "draft-1", DraftKey("event-1"), "thread-1", "other", "+15551234567", 7, "Hello",
        )
        assertStopped(fixture, DraftStopReason.INVALID_RECEIPT)
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `blank model output is rejected without a draft write`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ -> " \n\t " }
        assertStopped(fixture, DraftStopReason.EMPTY_REPLY)
    }

    @Test
    fun `oversized model output is rejected without truncation or persistence`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ -> "x".repeat(801) }
        assertStopped(fixture, DraftStopReason.REPLY_LIMIT)
    }

    @Test
    fun `model failure does not claim a draft or invoke persistence`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ -> throw IllegalStateException("Inference unavailable") }
        assertEquals(DraftOutcome.Failed(DraftFailureStage.MODEL), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.store.holdAttempts)
        assertEquals(0, fixture.store.receipts.size)
    }

    @Test
    fun `oversized incoming message is rejected before inference`() {
        val fixture = DraftFixture()
        fixture.store.source = fixture.initialSource.copy(inboundText = "x".repeat(4_001))
        assertStopped(fixture, DraftStopReason.CONTEXT_LIMIT)
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `memory count and total text are separately bounded`() {
        val fixture = DraftFixture()
        fixture.notes = List(13) { ContactMemory("contact-1", "a") }
        assertStopped(fixture, DraftStopReason.CONTEXT_LIMIT)
        fixture.notes = listOf(ContactMemory("contact-1", "x".repeat(8_001)))
        assertStopped(fixture, DraftStopReason.CONTEXT_LIMIT)
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `missing source and blank recipient are rejected`() {
        val fixture = DraftFixture()
        fixture.store.source = null
        assertStopped(fixture, DraftStopReason.SOURCE_MISSING)
        fixture.store.source = fixture.initialSource.copy(recipient = " ")
        assertStopped(fixture, DraftStopReason.INVALID_CONTEXT)
        assertEquals(0, fixture.modelCalls)
    }

    private fun assertStopped(fixture: DraftFixture, reason: DraftStopReason) {
        assertEquals(DraftOutcome.Stopped(reason), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.store.holdAttempts)
        assertEquals(0, fixture.store.inserts)
    }
}
