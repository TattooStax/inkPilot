package org.fossify.messages.messaging

import org.junit.Assert.*
import org.junit.Test

class ExactSubscriptionUnitTest {
    @Test fun explicitManagerFailureNeverFallsBackAndDispatchIsUnknownWithoutSending() {
        for (selected in listOf(31, 72)) {
            var requestedSubscription: Int? = null
            var defaultCalls = 0
            var sends = 0
            val request = OutgoingMessage(123, "+12025550123", "human body", emptyList(), selected)
            val outcome = MessageDispatcher({ true }, { true }) {
                selectSmsManager(it.subscriptionId, { id ->
                    requestedSubscription = id
                    throw IllegalStateException("subscription unavailable")
                }, {
                    defaultCalls++
                    error("must not fall back to another SIM")
                })
                sends++
                DispatchOutcome.Accepted()
            }.submit(request)
            assertEquals(selected, requestedSubscription)
            assertEquals(0, defaultCalls)
            assertEquals(0, sends)
            assertTrue(outcome is DispatchOutcome.Unknown)
        }
    }
}
