package org.fossify.messages.inkpilot.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarrierIngressPolicyTest {
    @Test fun nativeCommitMustHaveBothProviderAndThreadIdentity() {
        assertNull(CarrierIngressPolicy.nativeSource(0, 7))
        assertNull(CarrierIngressPolicy.nativeSource(-1, 7))
        assertNull(CarrierIngressPolicy.nativeSource(4, 0))
        assertEquals("sms:42", CarrierIngressPolicy.nativeSource(42, 7))
    }

    @Test fun subscriptionsAreIdsNeverPositionsAndAmbiguityFailsClosed() {
        val active = setOf(3, 7)
        assertEquals(7, CarrierIngressPolicy.exactSubscription(7, active))
        assertNull(CarrierIngressPolicy.exactSubscription(1, active))
        assertNull(CarrierIngressPolicy.exactSubscription(null, active))
        assertEquals(3, CarrierIngressPolicy.exactSubscription(null, setOf(3)))
        assertNull(CarrierIngressPolicy.exactSubscription(null, emptySet()))
    }

    @Test fun mmsCallbacksOnlyResolveOnePositiveNativeProviderRow() {
        assertEquals(42L, CarrierIngressPolicy.mmsProviderId("content", "mms", listOf("42")))
        assertEquals(42L, CarrierIngressPolicy.mmsProviderId("content", "mms", listOf("inbox", "42")))
        assertNull(CarrierIngressPolicy.mmsProviderId("file", null, listOf("private")))
        assertNull(CarrierIngressPolicy.mmsProviderId("content", "media", listOf("42")))
        assertNull(CarrierIngressPolicy.mmsProviderId("content", "mms", listOf("42", "part")))
        assertNull(CarrierIngressPolicy.mmsProviderId("content", "mms", listOf("0")))
    }

    @Test fun replyTokenBindsOriginThreadAndNativeMessageId() {
        assertEquals(CarrierIngressPolicy.ReplyBinding(7, "sms", 42),
            CarrierIngressPolicy.replyBinding("inkpilot-reply://thread/7/sms/42"))
        assertEquals(CarrierIngressPolicy.ReplyBinding(7, "mms", 42),
            CarrierIngressPolicy.replyBinding("inkpilot-reply://thread/7/mms/42"))
        assertNull(CarrierIngressPolicy.replyBinding("inkpilot-reply://thread/7/sms/0"))
        assertNull(CarrierIngressPolicy.replyBinding("inkpilot-reply://thread/7/other/42"))
        assertNull(CarrierIngressPolicy.replyBinding("https://thread/7/sms/42"))
    }

    @Test fun respondViaMessageAcceptsOnlySupportedSchemeAndSingleRecipient() {
        assertEquals("+12025550123", CarrierIngressPolicy.respondRecipient("smsto", "+12025550123"))
        assertNull(CarrierIngressPolicy.respondRecipient("http", "+12025550123"))
        assertNull(CarrierIngressPolicy.respondRecipient("sms", "+12025550123,+12025550124"))
        assertNull(CarrierIngressPolicy.respondRecipient("mms", ""))
        assertNull(CarrierIngressPolicy.respondRecipient("smsto", "//+12025550123"))
    }
}
