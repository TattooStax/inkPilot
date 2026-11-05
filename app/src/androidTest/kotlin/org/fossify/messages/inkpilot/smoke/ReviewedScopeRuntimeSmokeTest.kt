package org.fossify.messages.inkpilot.smoke

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.HeldDraftWrite
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.fossify.messages.inkpilot.data.DraftRegenerationOutcome
import org.fossify.messages.inkpilot.data.LeadIntakeReviewResult
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.reviewLeadIntake
import org.fossify.messages.inkpilot.data.staffAttention
import org.fossify.messages.inkpilot.runtime.AgentRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real SMS role and installed model; a reviewed scope never reaches RAW inference. */
@RunWith(AndroidJUnit4::class)
class ReviewedScopeRuntimeSmokeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val proof: File get() = File(context.filesDir, "device-test-evidence/reviewed-scope.json")

    @Test fun productionWorkerBlocksScopedEventAndExplicitRegeneration() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val roles = context.getSystemService(RoleManager::class.java)
        assertTrue(roles.isRoleHeld(RoleManager.ROLE_SMS))
        assertTrue(File(context.filesDir, "models/active.gguf").isFile)
        val before = outgoingMessages()
        synchronized(AgentRuntime.inferenceLock) {
            StudioRepository(context).use { repository ->
                assertTrue(repository.pendingRequests().isEmpty())
                repository.setDraftingEnabled(false)
                val arrivedAt = System.currentTimeMillis()
                val queued = repository.recordInbound(
                    "smoke:review-scope-pending", 900_131, "+12025550131",
                    "Can I book a consultation?", "Synthetic review scope", arrivedAt, -1,
                )
                val queuedReview = repository.reviewLeadIntake(queued, ReplyLanguage.EN,
                    setOf(LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved
                val priorHeld = repository.recordInbound(
                    "smoke:review-scope-held", 900_132, "+12025550132",
                    "Could I book a tattoo?", "Synthetic older draft", arrivedAt + 1, -1,
                )
                val oldBody = "A staff-written synthetic test reply"
                val write = HeldDraftWrite(DraftKey(priorHeld.eventId), priorHeld.threadId,
                    priorHeld.contactId, "+12025550132", priorHeld.expectedRevision, oldBody)
                // holdIfCurrent enforces the drafting switch; create this fixture before
                // pausing again for the staff review/revision transition.
                repository.setDraftingEnabled(true)
                assertTrue(repository.holdIfCurrent(write) is DraftStoreResult.Held)
                repository.setDraftingEnabled(false)
                val heldReview = repository.reviewLeadIntake(priorHeld, ReplyLanguage.EN,
                    setOf(LeadQuestion.PLACEMENT)) as LeadIntakeReviewResult.Saved
                val queuedSource = repository.readSource(queued.eventId)
                val heldSource = repository.readSource(priorHeld.eventId)
                repository.setDraftingEnabled(true)
                assertEquals(listOf(queued.eventId), repository.pendingRequests().map { it.eventId })

                assertEquals(0, AgentRuntime.processPending(context))
                assertEquals(queuedSource, repository.readSource(queued.eventId))
                assertNull(repository.findHeldDraft(DraftKey(queued.eventId)))
                val attention = repository.staffAttention().single { it.eventId == queued.eventId && it.current }
                assertEquals("REVIEW_SCOPE_UNAVAILABLE", attention.reason)
                assertEquals(queuedReview.request.expectedRevision, attention.sourceRevision)
                assertTrue(repository.pendingRequests().isEmpty())

                assertEquals(DraftRegenerationOutcome.ReviewScopeUnavailable,
                    AgentRuntime.regenerate(context, priorHeld.eventId, heldReview.request.expectedRevision))
                assertEquals(heldSource, repository.readSource(priorHeld.eventId))
                assertFalse(repository.heldDrafts().single { it.eventId == priorHeld.eventId }.canReview)
                repository.database.rawQuery("SELECT COUNT(*) FROM draft_regenerations WHERE event_id = ?",
                    arrayOf(priorHeld.eventId)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                assertEquals(before, outgoingMessages())
                proof.parentFile!!.mkdirs()
                proof.writeText(JSONObject().apply {
                    put("queuedEventId", queued.eventId)
                    put("queuedRevision", queuedReview.request.expectedRevision)
                    put("heldEventId", priorHeld.eventId)
                    put("heldRevision", heldReview.request.expectedRevision)
                    put("outgoingBefore", before)
                    put("outcome", "STAFF_ATTENTION_NO_RAW_DRAFT")
                    put("carrierDeliveryTested", false)
                }.toString(2))
            }
        }
    }

    @Test fun blockedScopeSurvivesProcessRestart() {
        val data = JSONObject(proof.readText())
        StudioRepository(context).use { repository ->
            val eventId = data.getString("queuedEventId")
            assertNull(repository.findHeldDraft(DraftKey(eventId)))
            val attention = repository.staffAttention().single { it.eventId == eventId && it.current }
            assertEquals("REVIEW_SCOPE_UNAVAILABLE", attention.reason)
            assertEquals(data.getLong("queuedRevision"), attention.sourceRevision)
            assertEquals(0, AgentRuntime.processPending(context))
            assertNull(repository.findHeldDraft(DraftKey(eventId)))
            assertEquals(data.getInt("outgoingBefore"), outgoingMessages())
        }
    }

    private fun outgoingMessages(): Int = context.contentResolver.query(
        Telephony.Sms.CONTENT_URI, arrayOf("_id"), "type IN (2, 4, 5, 6)", null, null,
    ).use { requireNotNull(it).count }
}
