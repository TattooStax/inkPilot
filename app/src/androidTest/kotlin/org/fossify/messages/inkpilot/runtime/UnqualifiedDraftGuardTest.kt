package org.fossify.messages.inkpilot.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestWorkerBuilder
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.HeldDraftWrite
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.ContactMatch
import org.fossify.messages.inkpilot.data.staffAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executor

/** Simulates an upgraded install whose saved ON bit and RAW held reply predate model qualification. */
@RunWith(AndroidJUnit4::class)
class UnqualifiedDraftGuardTest {
    @Test fun preexistingEnabledDatabaseCannotWakeInferenceOrSendAnOldReply() {
        assertFalse(AgentRuntime.BOUNDED_INTENT_QUALIFIED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = StudioRepository(context)
        val suffix = UUID.randomUUID().toString()
        val pendingId = "guard:pending:$suffix"
        val heldId = "guard:held:$suffix"
        val eventIds = arrayOf(pendingId, heldId)
        val contacts = mutableListOf<String>()
        val digits = suffix.filter(Char::isDigit).padEnd(8, '0')
        val pendingNumber = "+1202${digits.take(7)}"
        val heldNumber = "+1202${digits.takeLast(7)}"
        try {
            check(pendingNumber != heldNumber && repository.resolveContact(pendingNumber, "") === ContactMatch.Missing &&
                repository.resolveContact(heldNumber, "") === ContactMatch.Missing)
            repository.setDraftingEnabled(false)
            val pending = repository.recordInbound(pendingId, 911_101, pendingNumber, "Pending synthetic question", "Test", 10_001, -1)
            contacts.add(pending.contactId)
            assertFalse(AgentSettings(context).enabled)
            assertTrue("An always-OFF install leaves inbound SMS in the ordinary inbox",
                repository.staffAttention().none { it.eventId == pendingId })
            repository.setDraftingEnabled(true) // Persisted by a previous build, bypassing the current UI.
            val held = repository.recordInbound(heldId, 911_102, heldNumber, "Older synthetic question", "Test", 10_002, -1)
            contacts.add(held.contactId)
            val source = requireNotNull(repository.readSource(heldId))
            val saved = repository.holdIfCurrent(HeldDraftWrite(DraftKey(heldId), held.threadId, held.contactId,
                source.recipient, source.revision, "Older RAW reply")) as DraftStoreResult.Held
            assertTrue(repository.pendingRequests().any { it.eventId == pendingId })
            assertTrue(repository.isDraftingEnabled())

            val worker = TestWorkerBuilder<LocalDraftWorker>(context, Executor { it.run() })
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String,
                                              workerParameters: WorkerParameters): ListenableWorker =
                        object : LocalDraftWorker(appContext, workerParameters) {
                            override fun processDrafts(): Unit = error("Unqualified worker reached inference")
                        }
                }).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertFalse(repository.isDraftingEnabled())
            assertFalse(AgentSettings(context).enabled)
            assertEquals(0, AgentRuntime.processPending(context))
            assertNull(repository.findHeldDraft(DraftKey(pendingId)))
            assertFalse(repository.drafts().single { it.eventId == heldId }.canReview)
            assertEquals("MODEL_UNQUALIFIED", repository.staffAttention().single { it.eventId == pendingId && it.current }.reason)
            assertEquals("MODEL_UNQUALIFIED", repository.staffAttention().single { it.eventId == heldId && it.current }.reason)
            val blocked = assertThrows(IllegalStateException::class.java) {
                // Even if the model gate regresses, no active SIM can match this fixture.
                AgentRuntime.approveAndSend(context, saved.receipt.draftId, saved.receipt.body, -1)
            }
            assertEquals("Model drafts are unqualified. Reply directly in the conversation.", blocked.message)
            assertNull(repository.getSendClaim(saved.receipt.draftId))
            StudioRepository(context).use { reopened ->
                assertFalse(reopened.isDraftingEnabled())
                assertEquals(0, AgentRuntime.processPending(context))
                assertEquals(2, reopened.staffAttention().count { it.eventId in eventIds && it.current })
            }
        } finally {
            val db = repository.database
            db.beginTransaction()
            try {
                for (id in eventIds) {
                    db.delete("staff_attention", "event_id = ?", arrayOf(id))
                    db.delete("held_drafts", "event_id = ?", arrayOf(id))
                    db.delete("inbound_events", "event_id = ?", arrayOf(id))
                }
                for (id in contacts) {
                    db.delete("contact_aliases", "contact_id = ?", arrayOf(id))
                    db.delete("studio_contacts", "id = ?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                repository.close()
            }
        }
    }
}
