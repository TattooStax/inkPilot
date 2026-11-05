package org.fossify.messages.receivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SendStatusCallbackUnitTest {
    @Test fun deliveryReportIsParsedBeforeAppCacheIsUpdated() {
        var parsedDeliveryStatus: Int? = null
        var cachedDeliveryStatus: Int? = null
        finishSendStatusCallback(
            updateNative = { parsedDeliveryStatus = 0; true },
            updateApp = { verified -> if (verified) cachedDeliveryStatus = parsedDeliveryStatus },
        )
        assertEquals(0, cachedDeliveryStatus)
    }

    @Test fun zeroRowOrMismatchedProviderReadbackCannotConfirmCallback() {
        var readCalled = false
        assertEquals(false, nativeStatusUpdateVerified(2,
            update = { 0 }, read = { readCalled = true; 2 }))
        assertEquals(false, readCalled)
        assertEquals(false, nativeStatusUpdateVerified(2,
            update = { 1 }, read = { 1 }))
        assertEquals(true, nativeStatusUpdateVerified(2,
            update = { 1 }, read = { 2 }))
        var localConfirmed = false
        finishSendStatusCallback(
            updateNative = { nativeStatusUpdateVerified(2, update = { 0 }, read = { 2 }) },
            updateApp = { verified -> localConfirmed = verified },
        )
        assertEquals(false, localConfirmed)
    }

    @Test fun failedNativeUpdateDoesNotRecordAnUnverifiedLocalVerdict() {
        var cachedDeliveryStatus: Int? = null
        try {
            finishSendStatusCallback(
                updateNative = { error("Native row could not be read") },
                updateApp = { cachedDeliveryStatus = 0 },
            )
            fail("Native failure must propagate to the receiver's finally block")
        } catch (_: IllegalStateException) {
            assertNull(cachedDeliveryStatus)
        }
    }
}
