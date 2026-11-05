package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.messaging.key
import org.fossify.messages.models.Message
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Model-only fixture: no provider, database, role, or device state mutations. */
@RunWith(AndroidJUnit4::class)
class MessageKeyTest {
    @Test fun equalIdsRemainDistinctForAllFourOrigins() {
        val messages = MessageOrigin.entries.map { origin ->
            Message(
                id = 42, body = "body", type = 2, status = 0, participants = arrayListOf(),
                date = 100, read = false, threadId = 9,
                isMMS = origin == MessageOrigin.MMS || origin == MessageOrigin.SCHEDULED_MMS,
                attachment = null, senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
                subscriptionId = -1,
                isScheduled = origin == MessageOrigin.SCHEDULED_SMS || origin == MessageOrigin.SCHEDULED_MMS
            )
        }
        assertEquals(MessageOrigin.entries, messages.map { it.key().origin })
        assertEquals(4, messages.map { it.key() }.toSet().size)
        assertEquals(4, messages.map { it.getStableId() }.toSet().size)
        assertEquals(4, messages.map { it.getSelectionKey() }.toSet().size)
        for (message in messages) {
            val reloaded = message.copy(body = "edited")
            assertTrue(Message.areItemsTheSame(message, reloaded))
            assertEquals(message.getStableId(), reloaded.getStableId())
            assertEquals(message.getSelectionKey(), reloaded.getSelectionKey())
            for (other in messages.filter { it.key() != message.key() }) {
                assertFalse(Message.areItemsTheSame(message, other))
            }
        }
    }
}
