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
    fun moreThan200RowsResumeFromDurablePageAcrossRestart() {
        var latest = afterId
        repeat(405) { latest = insertInbox("+12025550991", "Synthetic page row $it") }
        repository.advanceIntakeCheckpoint(afterId)
        assertTrue(SmsIntakeRecovery.recoverPages(context, repository, since, maxPages = 1))
        val firstPage = repository.intakeCheckpoint()
        assertTrue(firstPage > afterId && firstPage < latest)
        reopenRepository()
        assertTrue(SmsIntakeRecovery.recoverPages(context, repository, since, maxPages = 1))
        reopenRepository()
        assertFalse(SmsIntakeRecovery.recoverPages(context, repository, since))
        assertEquals(latest, repository.intakeCheckpoint())
        assertEquals("sms:$latest", repository.contacts().single().latestEventId)
        assertEquals("Synthetic page row 404", nativeBody(latest))
        assertTrue(repository.heldDrafts().isEmpty())
    }

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
    fun quarantinedNativeRowRemainsStaffReviewOnlyAcrossRestart() {
        val body = "Synthetic unadmitted ${java.util.UUID.randomUUID()}"
        val id = insertInbox("+12025550991", body, admitted = false)
        repository.advanceIntakeCheckpoint(afterId)
        assertFalse(SmsIntakeRecovery.recoverPages(context, repository, since))
        assertEquals(id, repository.intakeCheckpoint())
        assertNull(repository.readSource("sms:$id"))
        assertTrue(repository.contacts().isEmpty())
        FilteredSmsJournal(context).use { journal ->
            assertEquals("native_unadmitted", journal.latest(100).first { it.body == body }.reason)
        }
        reopenRepository()
        assertFalse(SmsIntakeRecovery.recoverPages(context, repository, since))
        assertNull(repository.readSource("sms:$id"))
        assertEquals(body, nativeBody(id))
    }

    @Test
    fun existingFilteredReviewDoesNotDuplicateNativeQuarantineReview() {
        val sender = "+12025550991"
        val body = "Synthetic filter receipt before failure ${java.util.UUID.randomUUID()}"
        val id = insertInbox(sender, body, admitted = false)
        FilteredSmsJournal(context).use { journal ->
            try {
                journal.record(sender, body, since, -1, "blocked")
                assertEquals(id, SmsIntakeRecovery.scan(context, repository, afterId, since, 10))
                assertEquals(listOf("blocked"), journal.latest(100).filter { it.body == body }.map { it.reason })
                assertNull(repository.readSource("sms:$id"))
            } finally {
                journal.latest(100).filter { it.body == body }.forEach { journal.remove(it.receipt) }
            }
        }
    }

    @Test
    fun admittedNativeRowReplaysAfterProcessRestartAndPrunesReceipt() {
        val body = "Synthetic approved after native insert"
        val id = insertInbox("+12025550991", body)
        repository.advanceIntakeCheckpoint(afterId)
        FilteredSmsJournal(context).use { assertTrue(it.admissions.hasAdmission(id)) }
        reopenRepository()
        assertFalse(SmsIntakeRecovery.recoverPages(context, repository, since))
        assertEquals(body, repository.readSource("sms:$id")?.inboundText)
        FilteredSmsJournal(context).use { assertFalse(it.admissions.hasAdmission(id)) }
    }

    @Test
    fun reusedProviderIdWithDifferentBodyDoesNotReplayOldAdmission() {
        val body = "Synthetic reused provider ID ${java.util.UUID.randomUUID()}"
        val id = insertInbox("+12025550991", body, admitted = false)
        FilteredSmsJournal(context).use { journal ->
            val thread = Telephony.Threads.getOrCreateThreadId(context, "+12025550991")
            assertFalse(journal.admissions.admitFromProvider(context, id,
                SmsAdmissionLedger.NativeIdentity(thread, "+12025550991", "old body", since, -1)))
            assertFalse(journal.admissions.hasAdmission(id))
            journal.admissions.admitNative(id, thread, "+12025550991", "old body", since, -1)
        }
        assertEquals(id, SmsIntakeRecovery.scan(context, repository, afterId, since, 10))
        assertNull(repository.readSource("sms:$id"))
        assertTrue(repository.contacts().isEmpty())
        FilteredSmsJournal(context).use { journal ->
            assertEquals("native_unadmitted", journal.latest(100).first { it.body == body }.reason)
        }
    }

    @Test
    fun actualDatabaseFailureAbortsCheckpointProgressAndLeavesNativeMessageIntact() {
        val id = insertInbox("+12025550991", "Retained after storage failure")
        repository.advanceIntakeCheckpoint(afterId)
        repository.database.execSQL("DROP TABLE studio_contacts")
        assertThrows(SQLiteException::class.java) {
            SmsIntakeRecovery.recoverPages(context, repository, since)
        }
        assertEquals(afterId, repository.intakeCheckpoint())
        assertEquals("Retained after storage failure", nativeBody(id))
    }
}
