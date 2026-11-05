package org.fossify.messages.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadlessReplyAdmissionTest {
    @Test fun providerLookupFailureStillCommitsHumanTextBeforeAnyDispatch() {
        val persisted = mutableListOf<Pair<Long?, Int?>>()
        val result = HeadlessReplyAdmission.preserve(
            resolveThread = { throw IllegalStateException("provider unavailable") },
            resolveSubscription = { 7 },
            persist = { thread, subscription ->
                persisted += thread to subscription
                "held"
            },
        )
        assertEquals("held", result)
        assertNull(persisted.single().first)
        assertEquals(7, persisted.single().second)
    }

    @Test fun invalidThreadAndSubscriptionLookupFailureAreStillPersisted() {
        val persisted = mutableListOf<Pair<Long?, Int?>>()
        HeadlessReplyAdmission.preserve(
            resolveThread = { 0L },
            resolveSubscription = { throw IllegalStateException("settings unavailable") },
            persist = { thread, subscription ->
                persisted += thread to subscription
            },
        )
        assertEquals(1, persisted.size)
        assertNull(persisted.single().first)
        assertNull(persisted.single().second)
    }
}
