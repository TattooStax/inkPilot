package inkpilot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerServiceDraftsTest {
    @Test
    fun `one local inference creates a held reply bound to the authoritative recipient`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ -> "  What day would suit you?  " }

        val result = fixture.coordinator().draft(fixture.request) as DraftOutcome.Held

        assertEquals(DraftDisposition.CREATED, result.disposition)
        assertEquals("What day would suit you?", result.receipt.body)
        assertEquals(fixture.initialSource.recipient, result.receipt.recipient)
        assertEquals(fixture.request.contactId, result.receipt.contactId)
        assertEquals(fixture.request.expectedRevision, result.receipt.sourceRevision)
        assertEquals(1, fixture.modelCalls)
        assertEquals(1, fixture.store.inserts)
        assertEquals(listOf(fixture.notes.single().text), fixture.lastPrompt!!.contactMemory)
        assertEquals(800, fixture.lastPrompt!!.maximumReplyCharacters)
        assertTrue(fixture.lastPrompt!!.instructions.contains("untrusted data"))
    }

    @Test
    fun `replay through a new coordinator returns the existing receipt without another inference`() {
        val fixture = DraftFixture()
        val first = fixture.coordinator().draft(fixture.request) as DraftOutcome.Held

        val replay = fixture.coordinator().draft(fixture.request) as DraftOutcome.Held

        assertEquals(first.receipt, replay.receipt)
        assertEquals(DraftDisposition.ALREADY_HELD, replay.disposition)
        assertEquals(1, fixture.modelCalls)
        assertEquals(1, fixture.store.holdAttempts)
        assertEquals(1, fixture.store.inserts)
    }

    @Test
    fun `competing insertion returns the original receipt even if generated text differs`() {
        val fixture = DraftFixture()
        fixture.store.beforeHold = { write ->
            fixture.store.receipts[write.key] = receiptFor(write.copy(body = "A reply already held by another run."))
        }

        val result = fixture.coordinator().draft(fixture.request) as DraftOutcome.Held

        assertEquals(DraftDisposition.ALREADY_HELD, result.disposition)
        assertEquals("A reply already held by another run.", result.receipt.body)
        assertEquals(1, fixture.store.receipts.size)
        assertEquals(0, fixture.store.inserts)
    }

    @Test
    fun `model text cannot change persisted recipient or contact identity`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ -> "{\"recipient\":\"+19990000000\",\"contactId\":\"other\"}" }

        val result = fixture.coordinator().draft(fixture.request) as DraftOutcome.Held

        assertEquals(fixture.initialSource.recipient, result.receipt.recipient)
        assertEquals(fixture.initialSource.contactId, result.receipt.contactId)
        assertEquals(fixture.initialSource.threadId, result.receipt.threadId)
        assertEquals(fixture.initialSource.eventId, result.receipt.key.eventId)
    }

    @Test
    fun `unknown commit acknowledgement is recoverable by the same durable key`() {
        val fixture = DraftFixture()
        fixture.store.throwAfterCommit = true

        assertEquals(
            DraftOutcome.PersistenceUnconfirmed(DraftKey(fixture.request.eventId)),
            fixture.coordinator().draft(fixture.request),
        )
        val replay = fixture.coordinator().draft(fixture.request) as DraftOutcome.Held
        assertEquals(DraftDisposition.ALREADY_HELD, replay.disposition)
        assertEquals(1, fixture.modelCalls)
        assertEquals(1, fixture.store.inserts)
    }

    @Test
    fun `a mismatched commit receipt never reports a confirmed draft`() {
        val fixture = DraftFixture()
        fixture.store.receiptTransform = { it.copy(contactId = "other") }

        assertEquals(
            DraftOutcome.PersistenceUnconfirmed(DraftKey(fixture.request.eventId)),
            fixture.coordinator().draft(fixture.request),
        )
    }

    @Test
    fun `an altered newly committed body never reports the requested draft as held`() {
        val fixture = DraftFixture()
        fixture.store.receiptTransform = { it.copy(body = "A different reply") }

        assertEquals(
            DraftOutcome.PersistenceUnconfirmed(DraftKey(fixture.request.eventId)),
            fixture.coordinator().draft(fixture.request),
        )
    }
}
