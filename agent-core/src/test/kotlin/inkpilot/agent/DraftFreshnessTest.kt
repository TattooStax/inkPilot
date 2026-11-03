package inkpilot.agent

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftFreshnessTest {
    @Test
    fun `stale trigger does not run inference`() {
        val fixture = DraftFixture()
        fixture.store.source = fixture.initialSource.copy(revision = 8)
        assertEquals(DraftOutcome.Stopped(DraftStopReason.SOURCE_CHANGED), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `thread revision changed during inference cannot commit the reply`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ ->
            fixture.store.source = fixture.initialSource.copy(revision = 8)
            "An outdated response"
        }
        assertEquals(DraftOutcome.Stopped(DraftStopReason.SOURCE_CHANGED), fixture.coordinator().draft(fixture.request))
        assertEquals(1, fixture.store.holdAttempts)
        assertEquals(0, fixture.store.inserts)
    }

    @Test
    fun `staff pause prevents inference`() {
        val fixture = DraftFixture()
        fixture.store.source = fixture.initialSource.copy(staffPaused = true)
        assertEquals(DraftOutcome.Stopped(DraftStopReason.STAFF_PAUSED), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.modelCalls)
    }

    @Test
    fun `staff pause immediately before commit prevents persistence`() {
        val fixture = DraftFixture()
        fixture.store.beforeHold = { fixture.store.source = fixture.initialSource.copy(staffPaused = true, revision = 8) }
        assertEquals(DraftOutcome.Stopped(DraftStopReason.STAFF_PAUSED), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.store.inserts)
    }

    @Test
    fun `recipient change immediately before commit prevents rerouting the reply`() {
        val fixture = DraftFixture()
        fixture.store.beforeHold = { fixture.store.source = fixture.initialSource.copy(recipient = "+19990000000") }
        assertEquals(DraftOutcome.Stopped(DraftStopReason.SOURCE_CHANGED), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.store.inserts)
    }

    @Test
    fun `cancellation before work or during inference never attempts a write`() {
        val fixture = DraftFixture()
        assertEquals(
            DraftOutcome.Stopped(DraftStopReason.CANCELLED),
            fixture.coordinator().draft(fixture.request, DraftCancellation { true }),
        )
        assertEquals(0, fixture.modelCalls)
        var cancelled = false
        fixture.generate = { _, cancellation ->
            assertEquals(false, cancellation.isCancelled())
            cancelled = true
            "No longer wanted"
        }
        assertEquals(
            DraftOutcome.Stopped(DraftStopReason.CANCELLED),
            fixture.coordinator().draft(fixture.request, DraftCancellation { cancelled }),
        )
        assertEquals(0, fixture.store.holdAttempts)
    }

    @Test
    fun `durable cancellation at commit is enforced by revision invalidation`() {
        val fixture = DraftFixture()
        fixture.store.beforeHold = { fixture.store.source = fixture.initialSource.copy(revision = 8) }
        assertEquals(DraftOutcome.Stopped(DraftStopReason.SOURCE_CHANGED), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.store.inserts)
    }

    @Test
    fun `model cancellation is not reported as an inference failure`() {
        val fixture = DraftFixture()
        fixture.generate = { _, _ -> throw CancellationException() }
        assertEquals(DraftOutcome.Stopped(DraftStopReason.CANCELLED), fixture.coordinator().draft(fixture.request))
        assertEquals(0, fixture.store.holdAttempts)
    }
}
