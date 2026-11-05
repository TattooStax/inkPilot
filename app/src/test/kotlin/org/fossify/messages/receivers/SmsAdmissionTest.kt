package org.fossify.messages.receivers

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsAdmissionTest {
    @Test fun failingKeywordOrContactLookupFallsBackToNativeQuarantine() {
        var native = 0
        var ordinary = 0
        var uncertain = 0
        val result = SmsAdmission.process(
            classify = { throw IllegalStateException("contact query denied") },
            journal = { error("must not journal an unknown classification") },
            normalNative = { ordinary++ }, quarantinedNative = { native++ },
            onUncertain = { uncertain++ },
        )
        assertEquals(SmsAdmission.Result.QUARANTINED_NATIVE, result)
        assertEquals(1, native)
        assertEquals(0, ordinary)
        assertEquals(1, uncertain)
    }

    @Test fun failedPrivateReceiptKeepsFilteredArrivalInNativeProviderOnly() {
        var native = 0
        var ordinary = 0
        val result = SmsAdmission.process(
            classify = { "blocked" }, journal = { throw IllegalStateException("disk full") },
            normalNative = { ordinary++ }, quarantinedNative = { native++ }, onUncertain = {},
        )
        assertEquals(SmsAdmission.Result.QUARANTINED_NATIVE, result)
        assertEquals(1, native)
        assertEquals(0, ordinary)
    }

    @Test fun confirmedPrivateReceiptSuppressesNativeRowAndNormalDraft() {
        var native = 0
        var journaled = 0
        val result = SmsAdmission.process(
            classify = { "keyword" }, journal = { journaled++ }, normalNative = { native++ },
            quarantinedNative = { native++ }, onUncertain = {},
        )
        assertEquals(SmsAdmission.Result.FILTERED, result)
        assertEquals(1, journaled)
        assertEquals(0, native)
    }
}
