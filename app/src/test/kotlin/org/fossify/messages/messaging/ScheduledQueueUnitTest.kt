package org.fossify.messages.messaging

import org.junit.Assert.*
import org.junit.Test

class ScheduledQueueUnitTest {
    @Test fun alarmFailureKeepsPersistedQueuedItem() {
        val events = mutableListOf<String>()
        val outcome = persistThenPost({ events.add("persisted"); true }, {
            assertEquals(listOf("persisted"), events)
            events.add("post attempted")
            error("Alarm manager unavailable")
        })
        assertEquals(ScheduledQueueResult.POST_FAILED, outcome)
        assertEquals(listOf("persisted", "post attempted"), events)
    }
    @Test fun claimedEditCannotPersistOrPost() {
        var posted = false
        assertEquals(ScheduledQueueResult.CLAIMED, persistThenPost({ false }, { posted = true }))
        assertFalse(posted)
    }
    @Test fun normalQueuePersistsBeforePost() {
        var persisted = false
        assertEquals(ScheduledQueueResult.QUEUED, persistThenPost({ persisted = true; true }, { assertTrue(persisted) }))
    }
    @Test fun invalidSimAndUnreadableMediaRejectBeforeClaim() {
        assertNotNull(scheduledPreflight(-1, true, true) { error("must not read media") })
        assertNotNull(scheduledPreflight(3, true, true) { false })
        assertNull(scheduledPreflight(3, true, true) { true })
    }
}
