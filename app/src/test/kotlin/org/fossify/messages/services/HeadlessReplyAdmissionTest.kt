package org.fossify.messages.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadlessReplyAdmissionTest {
    @Test fun providerLookupFailureStillCommitsHumanTextBeforeAnyDispatch() {
        val persisted = mutableListOf<Pair<Long?, Int?>>()
        val admission = HeadlessReplyAdmission.preserve(
            resolveThread = { throw IllegalStateException("provider unavailable") },
            resolveSubscription = { 7 },
            persist = { thread, subscription ->
                persisted += thread to subscription
                "held"
            },
        )
        assertEquals("held", admission.held)
        assertNull(persisted.single().first)
        assertEquals(7, persisted.single().second)
    }

    @Test fun invalidThreadAndSubscriptionLookupFailureAreStillPersisted() {
        val persisted = mutableListOf<Pair<Long?, Int?>>()
        val admission = HeadlessReplyAdmission.preserve(
            resolveThread = { 0L },
            resolveSubscription = { throw IllegalStateException("settings unavailable") },
            persist = { thread, subscription ->
                persisted += thread to subscription
            },
        )
        assertEquals(1, persisted.size)
        assertNull(persisted.single().first)
        assertNull(persisted.single().second)
        assertEquals(HeadlessReplyAdmission.SavedSim.LookupFailed, admission.savedSim)
    }

    @Test fun validThreadFailedSavedSimLookupCannotDispatchWithOneActiveSim() {
        val persisted = mutableListOf<Pair<Long?, Int?>>()
        val admission = HeadlessReplyAdmission.preserve(
            resolveThread = { 42L },
            resolveSubscription = { throw IllegalStateException("saved SIM preference unreadable") },
            persist = { thread, subscription ->
                persisted += thread to subscription
                "human text held"
            },
        )
        assertEquals("human text held", admission.held)
        assertEquals(42L, persisted.single().first)
        assertNull(persisted.single().second)
        assertEquals(HeadlessReplyAdmission.SavedSim.LookupFailed, admission.savedSim)
        assertNull(admission.exactSubscription(setOf(7)))
    }

    @Test fun absentSavedPreferenceStillUsesSingleActiveSim() {
        val admission = HeadlessReplyAdmission.preserve(
            resolveThread = { 42L },
            resolveSubscription = { null },
            persist = { _: Long?, _: Int? -> "held" },
        )
        assertEquals(HeadlessReplyAdmission.SavedSim.Unspecified, admission.savedSim)
        assertEquals(7, admission.exactSubscription(setOf(7)))
        assertNull(admission.exactSubscription(setOf(7, 9)))
    }
}
