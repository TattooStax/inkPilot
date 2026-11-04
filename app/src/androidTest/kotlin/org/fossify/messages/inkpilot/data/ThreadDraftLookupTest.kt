package org.fossify.messages.inkpilot.data

import inkpilot.agent.DraftStoreResult
import org.junit.Assert.*
import org.junit.Test

class ThreadDraftLookupTest : StudioDatabaseTestBase() {
    @Test fun olderThreadAndDirectDraftRemainReachableBeyondGlobalWindow() {
        val original = held(inbound("sms:old", "+15550100001", 1))
        repeat(110) { i ->
            val request = repository.recordInbound("sms:new:$i", 1000L + i, "+155502${i.toString().padStart(5, '0')}",
                "Synthetic inbound", "Fixture", 10000L + i, 3)
            assertTrue(repository.holdIfCurrent(write(request)) is DraftStoreResult.Held)
        }
        assertEquals(100, repository.drafts().size)
        assertFalse(repository.drafts().any { it.draftId == original.draftId })
        reopen()
        assertEquals(original.draftId, repository.draftsForThread(42).single().draftId)
        assertEquals(original.body, repository.draft(original.draftId)?.body)
        assertNull(repository.draft("does-not-exist"))
    }
}
