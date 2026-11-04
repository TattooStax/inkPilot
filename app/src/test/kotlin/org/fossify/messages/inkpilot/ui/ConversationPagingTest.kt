package org.fossify.messages.inkpilot.ui

import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.junit.Assert.*
import org.junit.Test

class ConversationPagingTest {
    @Test fun sameTimestampUsesTypedOriginThenFullProviderId() {
        val after = MessageCursor(1234567890000, MessageKey(MessageOrigin.MMS, 1L shl 34))
        val sms = messageBoundary(after, MessageOrigin.SMS)
        assertEquals(" AND (date < ? OR (date = ? AND (1)))", sms.selection)
        assertEquals(listOf("1234567890000", "1234567890000"), sms.args)
        val mms = messageBoundary(after, MessageOrigin.MMS)
        assertEquals(" AND ((date * 1000) < ? OR ((date * 1000) = ? AND (_id < ?)))", mms.selection)
        assertEquals(listOf("1234567890000", "1234567890000", "17179869184"), mms.args)
    }
    @Test fun smsBoundaryExcludesMmsAtSameTimestampButRetainsOlderMms() {
        val after = MessageCursor(1001, MessageKey(MessageOrigin.SMS, 42))
        val mms = messageBoundary(after, MessageOrigin.MMS)
        assertTrue(mms.selection.contains("(date * 1000) < ?"))
        assertTrue(mms.selection.contains("AND (0)"))
        assertEquals(listOf("1001", "1001"), mms.args)
        val sms = messageBoundary(after, MessageOrigin.SMS)
        assertEquals(listOf("1001", "1001", "42"), sms.args)
    }
    @Test fun firstPageHasNoBoundaryOrArguments() {
        MessageOrigin.entries.take(2).forEach {
            assertEquals(MessageBoundary("", emptyList()), messageBoundary(null, it))
        }
    }
}
