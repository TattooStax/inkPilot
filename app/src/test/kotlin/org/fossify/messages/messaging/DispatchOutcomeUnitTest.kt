package org.fossify.messages.messaging

import org.junit.Assert.*
import org.junit.Test

class DispatchOutcomeUnitTest {
    private val request = OutgoingMessage(123L, "+12025550123", "unchanged human body", emptyList(), 42)
    @Test fun permissionDenialDoesNotReachHandoff() {
        var calls = 0
        val result = MessageDispatcher({ true }, { false }) { calls++; DispatchOutcome.Accepted() }.submit(request)
        assertTrue(result is DispatchOutcome.Rejected)
        assertEquals(0, calls)
    }
    @Test fun exactSimAndTextReachHandoffOnceAndThrowIsUnknown() {
        var calls = 0
        val result = MessageDispatcher({ true }, { true }) {
            calls++
            assertEquals(request, it)
            throw IllegalStateException("ambiguous transport failure")
        }.submit(request)
        assertTrue(result is DispatchOutcome.Unknown)
        assertEquals(1, calls)
    }
    @Test fun queueAcknowledgmentIsAcceptedWithoutDeliveryClaim() {
        val result = MessageDispatcher({ true }, { true }) { DispatchOutcome.Accepted() }.submit(request)
        assertEquals(DispatchOutcome.Accepted(), result)
    }
    @Test fun missingSubscriptionIsRejectedBeforeHandoff() {
        val result = MessageDispatcher({ true }, { true }) { error("must not send") }.submit(request.copy(subscriptionId = -1))
        assertTrue(result is DispatchOutcome.Rejected)
    }
}
