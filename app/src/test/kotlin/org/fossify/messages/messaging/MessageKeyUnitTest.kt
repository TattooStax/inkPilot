package org.fossify.messages.messaging

import org.fossify.messages.models.Message
import org.junit.Assert.*
import org.junit.Test

class MessageKeyUnitTest {
    @Test fun equalNumericIdsHaveFourIndependentIdentities() {
        val messages = origins(42)
        assertEquals(MessageOrigin.entries.toSet(), messages.map { it.key().origin }.toSet())
        assertEquals(4, messages.map { it.key() }.toSet().size)
        assertEquals(4, messages.map { it.getStableId() }.toSet().size)
        assertEquals(4, messages.map { it.getSelectionKey() }.toSet().size)
        for (old in messages) for (new in messages) {
            assertEquals(old.key() == new.key(), Message.areItemsTheSame(old, new))
        }
    }

    @Test fun uiNamespacesCannotAliasAcrossDifferentNumericIds() {
        val ids = listOf(0L, 42L, 969_835_325L, 1L shl 32, Long.MAX_VALUE)
        val messages = ids.flatMap { origins(it) }
        for (message in messages) for (other in messages) {
            if (message.key().origin != other.key().origin) {
                assertNotEquals(message.getSelectionKey(), other.getSelectionKey())
                assertNotEquals(message.getStableId(), other.getStableId())
            }
        }
    }

    @Test fun contentChangesPreserveIdentityAcrossReloads() {
        for (message in origins(42)) {
            val reloaded = message.copy(body = "edited", date = 200, read = true)
            assertEquals(message.key(), reloaded.key())
            assertEquals(message.getStableId(), reloaded.getStableId())
            assertEquals(message.getSelectionKey(), reloaded.getSelectionKey())
            assertTrue(Message.areItemsTheSame(message, reloaded))
            assertFalse(Message.areContentsTheSame(message, reloaded))
            assertFalse(Message.areItemsTheSame(message, message.copy(id = 43)))
        }
    }

    @Test fun fullLongIdIsRetainedInTypedIdentity() {
        val ids = listOf(0L, 42L, 1L shl 32, Long.MAX_VALUE, Long.MIN_VALUE)
        val keys = ids.flatMap { id -> origins(id).map { it.key() } }
        assertEquals(ids.size * 4, keys.toSet().size)
        assertEquals(keys, ids.flatMap { id -> origins(id).map { it.copy().key() } })
    }

    @Test fun stableIdsSeparateAdjacentRowsAndSelectionUsesHighIdBits() {
        val messages = (0L..100L).flatMap { origins(it) }
        assertEquals(messages.size, messages.map { it.getStableId() }.toSet().size)
        for (message in origins(42)) {
            assertNotEquals(message.getSelectionKey(), message.copy(id = 42L + (1L shl 32)).getSelectionKey())
        }
    }

    private fun origins(id: Long): List<Message> = listOf(
        message(id, false, false), message(id, true, false),
        message(id, false, true), message(id, true, true)
    )

    private fun message(id: Long, mms: Boolean, scheduled: Boolean) = Message(
        id = id, body = "body", type = 2, status = 0, participants = arrayListOf(),
        date = 100, read = false, threadId = 9, isMMS = mms, attachment = null,
        senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
        subscriptionId = -1, isScheduled = scheduled
    )
}
