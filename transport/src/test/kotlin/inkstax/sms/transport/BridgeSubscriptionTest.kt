package inkstax.sms.transport

import org.junit.Assert.*
import org.junit.Test

class BridgeSubscriptionTest {
    @Test fun receiptAndRadioUseTheSameCapturedActiveSubscription() {
        var snapshot = BridgeSubscriptionSnapshot(7, setOf(7, 8))
        val selected = BridgeSubscriptionSelection.capture { snapshot }!!
        val receiptSubscription = selected.subscriptionId
        var radioSubscription: Int? = null
        assertTrue(selected.dispatchIfCurrent { pinned ->
            // A later platform default change cannot redirect an explicit id.
            snapshot = BridgeSubscriptionSnapshot(8, setOf(7, 8))
            radioSubscription = pinned
        })
        assertEquals(7, receiptSubscription)
        assertEquals(receiptSubscription, radioSubscription)
    }

    @Test fun changedDefaultOrRemovedSimRefusesDispatchAfterPreparation() {
        for (after in listOf(BridgeSubscriptionSnapshot(8, setOf(7, 8)),
            BridgeSubscriptionSnapshot(7, setOf(8)))) {
            var snapshot = BridgeSubscriptionSnapshot(7, setOf(7, 8))
            val selected = BridgeSubscriptionSelection.capture { snapshot }!!
            snapshot = after
            var dispatched = false
            assertFalse(selected.dispatchIfCurrent { dispatched = true })
            assertFalse(dispatched)
        }
    }

    @Test fun missingInactiveOrUnreadableSubscriptionsNeverSelectFallback() {
        for (snapshot in listOf(BridgeSubscriptionSnapshot(-1, setOf(7)),
            BridgeSubscriptionSnapshot(Int.MAX_VALUE, setOf(Int.MAX_VALUE)),
            BridgeSubscriptionSnapshot(7, emptySet()))) {
            assertNull(BridgeSubscriptionSelection.capture { snapshot })
        }
        assertNull(BridgeSubscriptionSelection.capture { error("synthetic permission refusal") })
    }

    @Test fun permissionLossBeforeDispatchFailsClosed() {
        var denied = false
        val selected = BridgeSubscriptionSelection.capture {
            if (denied) error("synthetic permission refusal")
            BridgeSubscriptionSnapshot(7, setOf(7))
        }!!
        denied = true
        var dispatched = false
        assertFalse(selected.dispatchIfCurrent { dispatched = true })
        assertFalse(dispatched)
    }
}
