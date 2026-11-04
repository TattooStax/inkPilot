package org.fossify.messages.inkpilot.ui

import org.fossify.messages.models.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePresentationTest {
    @Test fun smsAndMmsDraftsNeverClaimSentOrDelivered() {
        listOf(false, true).forEach { assertEquals("Draft", message(it, 3, 0).deliveryLabel()) }
    }
    @Test fun sentSmsHonorsDeliveryReportWhileMmsUsesItsOwnBoxNamespace() {
        assertEquals("Sent", message(false, 2, -1).deliveryLabel())
        assertEquals("Delivered", message(false, 2, 0).deliveryLabel())
        assertEquals("Delivery pending", message(false, 2, 32).deliveryLabel())
        assertEquals("Delivery failed", message(false, 2, 64).deliveryLabel())
        assertEquals("Sent", message(true, 2, 0).deliveryLabel())
        assertEquals("Sending", message(true, 4, 0).deliveryLabel())
        assertEquals("Failed", message(true, 5, 0).deliveryLabel())
    }
    private fun message(mms: Boolean, type: Int, status: Int) = Message(7, "", type, status, arrayListOf(),
        100, false, 42, mms, null, "", "", "", -1)
}
