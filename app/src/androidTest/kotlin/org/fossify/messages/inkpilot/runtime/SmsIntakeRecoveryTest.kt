package org.fossify.messages.inkpilot.runtime

import android.database.sqlite.SQLiteException
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsIntakeRecoveryTest : SmsIntakeRecoveryTestBase() {
    @Test
    fun backfillAndReplayCommitIntakeWithoutChangingNativeHistory() {
        val body = "Synthetic recovery consultation"
        val id = insertInbox("+12025550991", body)
        assertEquals(id, SmsIntakeRecovery.scan(context, repository, afterId, since, 10))
        val contact = repository.contacts().single()
        assertEquals("sms:$id", contact.latestEventId)
        assertEquals(body, repository.readSource("sms:$id")?.inboundText)
        assertFalse("Recovery must also work before drafting is enabled", repository.isDraftingEnabled())
        assertEquals(id, SmsIntakeRecovery.scan(context, repository, afterId, since, 10))
        assertEquals(contact.revision, repository.contacts().single().revision)
        assertEquals(body, nativeBody(id))
        assertTrue(repository.heldDrafts().isEmpty())
    }

    @Test
    fun invalidRowsDoNotStarveLaterValidInboxRows() {
        insertInbox("not-replyable", "Invalid sender")
        insertInbox("+12025550991", " ")
        insertInbox("+12025550991", "x".repeat(16_001))
        val valid = insertInbox("+12025550991", "Valid latest request")
        val first = SmsIntakeRecovery.scan(context, repository, afterId, since, 2)
        assertTrue(repository.contacts().isEmpty())
        assertEquals(valid, SmsIntakeRecovery.scan(context, repository, first, since, 2))
        assertEquals("sms:$valid", repository.contacts().single().latestEventId)
        assertEquals("Valid latest request", nativeBody(valid))
    }

    @Test
    fun dateBoundaryAndInboxOnlySelectionExcludeEarlierHistoryAndOutgoingRows() {
        val old = insertInbox("+12025550992", "Earlier history", since - 1)
        val outgoing = insertInbox("+12025550993", "Outgoing fixture", type = Telephony.Sms.MESSAGE_TYPE_SENT)
        val current = insertInbox("+12025550991", "Current inbox")
        assertEquals(current, SmsIntakeRecovery.scan(context, repository, afterId, since, 10))
        assertEquals("+12025550991", repository.contacts().single().number)
        assertNull(repository.readSource("sms:$old"))
        assertNull(repository.readSource("sms:$outgoing"))
        assertEquals("Earlier history", nativeBody(old))
        assertEquals("Outgoing fixture", nativeBody(outgoing))
    }

    @Test
    fun actualDatabaseFailureAbortsCheckpointProgressAndLeavesNativeMessageIntact() {
        val id = insertInbox("+12025550991", "Retained after storage failure")
        repository.database.execSQL("DROP TABLE studio_contacts")
        var checkpoint = afterId
        assertThrows(SQLiteException::class.java) {
            checkpoint = SmsIntakeRecovery.scan(context, repository, afterId, since, 10)
        }
        assertEquals(afterId, checkpoint)
        assertEquals("Retained after storage failure", nativeBody(id))
    }
}
