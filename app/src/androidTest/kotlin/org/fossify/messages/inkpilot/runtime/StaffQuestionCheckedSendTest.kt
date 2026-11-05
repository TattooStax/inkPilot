package org.fossify.messages.inkpilot.runtime

import android.content.ContentUris
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.fossify.messages.inkpilot.data.LeadIntakeReviewResult
import org.fossify.messages.inkpilot.data.StaffQuestionResult
import org.fossify.messages.inkpilot.data.StudioDatabaseTestBase
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.holdStaffQuestion
import org.fossify.messages.inkpilot.data.reviewLeadIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic recipient on a disposable emulator only; provider submission is not carrier delivery. */
@RunWith(AndroidJUnit4::class)
class StaffQuestionCheckedSendTest : StudioDatabaseTestBase() {
    @Test fun selectedEmulatorSimSubmitsStaffQuestionWithModelGateOffAndCannotReplay() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val active = context.getSystemService(SubscriptionManager::class.java)
            .activeSubscriptionInfoList.orEmpty().firstOrNull()
        assumeTrue("The emulator has no active test SIM", active != null)
        val sim = requireNotNull(active)
        val recipient = "+12025550996"
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipient)
        repository.setDraftingEnabled(false)
        val eventId = "staff-send:${UUID.randomUUID()}"
        val inbound = repository.recordInbound(eventId, threadId, recipient,
            "What should I tell you about the tattoo?", "Synthetic test", System.currentTimeMillis(), sim.subscriptionId)
        val scope = (repository.reviewLeadIntake(inbound, ReplyLanguage.EN,
            setOf(LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved).request
        val id = (repository.holdStaffQuestion(scope, LeadQuestion.SIZE) as StaffQuestionResult.Held).draftId
        val held = requireNotNull(repository.draft(id))
        assertFalse(AgentRuntime.BOUNDED_INTENT_QUALIFIED)
        assertTrue(held.canReview)
        val sender = CheckedSmsSender(context) { StudioRepository(context, databaseName) }
        assertThrows(IllegalArgumentException::class.java) {
            sender.approveAndSend(id, held.body, Int.MAX_VALUE)
        }
        assertNull(repository.getSendClaim(id))
        var providerId: Long? = null
        try {
            assertEquals("Submitted to the selected SIM. Waiting for carrier confirmation.",
                sender.approveAndSend(id, held.body, sim.subscriptionId))
            val claim = requireNotNull(repository.getSendClaim(id))
            providerId = claim.providerId
            assertNotNull(providerId)
            assertEquals(sim.subscriptionId, claim.subscriptionId)
            val row = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId!!)
            context.contentResolver.query(row, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                null, null, null).use { cursor ->
                requireNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals(recipient, cursor.getString(0))
                assertEquals(held.body, cursor.getString(1))
            }
            assertThrows(IllegalStateException::class.java) {
                sender.approveAndSend(id, held.body, sim.subscriptionId)
            }
            assertEquals(providerId, repository.getSendClaim(id)?.providerId)
        } finally {
            providerId?.let {
                context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it), null, null)
            }
        }
    }
}
