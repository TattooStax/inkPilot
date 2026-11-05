package inkstax.sms.transport

import org.junit.Assert.*
import org.junit.Test

class BridgeReceiptReconciliationTest {
    @Test fun positiveUpdateSettlesWithoutPresenceQuery() {
        assertTrue(bridgeReceiptUpdateSettles(1) { error("must not query") })
    }

    @Test fun zeroUpdateWithExistingOutboxNeverSettles() {
        assertFalse(bridgeReceiptUpdateSettles(0) { true })
    }

    @Test fun zeroUpdateWithVerifiedDeletionSettles() {
        assertTrue(bridgeReceiptUpdateSettles(0) { false })
    }

    @Test fun zeroUpdateWithUnavailableOrDeniedQueryNeverSettles() {
        assertFalse(bridgeReceiptUpdateSettles(0) { null })
        assertFalse(bridgeReceiptUpdateSettles(0) { error("synthetic provider refusal") })
    }

    @Test fun invalidUpdateCountDoesNotMasqueradeAsDeletion() {
        assertFalse(bridgeReceiptUpdateSettles(-1) { error("must not query") })
    }
}
