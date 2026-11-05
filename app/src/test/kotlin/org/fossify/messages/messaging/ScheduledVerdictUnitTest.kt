package org.fossify.messages.messaging

import org.junit.Assert.*
import org.junit.Test

class ScheduledVerdictUnitTest {
    @Test fun mmsCallbackWithoutVerifiedProviderRowRemainsUnknown() {
        val calls = mutableListOf<String>()
        for (carrierAccepted in listOf(true, false)) {
            applyScheduledCallbackVerdict(false, carrierAccepted,
                record = { calls.add("record:$it") },
                unknown = { calls.add("unknown") })
        }
        assertEquals(listOf("unknown", "unknown"), calls)
    }
    @Test fun verifiedMmsCallbackRecordsCarrierResult() {
        val outcomes = mutableListOf<Boolean>()
        applyScheduledCallbackVerdict(true, true, outcomes::add, { fail("Verified row became unknown") })
        applyScheduledCallbackVerdict(true, false, outcomes::add, { fail("Verified row became unknown") })
        assertEquals(listOf(true, false), outcomes)
    }
    @Test fun lateSuccessAfterFailureRemainsUnknown() {
        assertEquals("UNKNOWN", partVerdict(listOf(2), 2))
        assertEquals("UNKNOWN", partVerdict(listOf(2, 1), 2))
        assertEquals("UNKNOWN", partVerdict(listOf(2, 0), 2))
    }
    @Test fun everyPartMustHaveAResult() {
        assertEquals("DISPATCHING", partVerdict(listOf(1), 2))
        assertEquals("CONFIRMED", partVerdict(listOf(1, 1), 2))
        assertEquals("FAILED", partVerdict(listOf(2, 2), 2))
    }
    @Test fun kindAndAllIdBitsAreInAlarmIdentity() {
        val sms = MessageKey(MessageOrigin.SCHEDULED_SMS, 42)
        assertNotEquals(sms.alarmIdentity(), MessageKey(MessageOrigin.SCHEDULED_MMS, 42).alarmIdentity())
        assertNotEquals(sms.alarmIdentity(), MessageKey(MessageOrigin.SCHEDULED_SMS, 42 + (1L shl 32)).alarmIdentity())
    }
}
