package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftRendererPolicy
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class StudioStaffQuestionTest : StudioDatabaseTestBase() {
    private fun review(questions: Set<LeadQuestion> = setOf(LeadQuestion.SIZE, LeadQuestion.DATE)) =
        (repository.reviewLeadIntake(inbound(), ReplyLanguage.ES, questions) as LeadIntakeReviewResult.Saved).request

    @Test fun staffChoiceIsHeldAcrossRestartWithModelDisabledAndRequiresAClaim() {
        repository.setDraftingEnabled(false)
        val request = review()
        val target = requireNotNull(repository.staffQuestionTarget(42))
        assertEquals(request, target.request)
        assertEquals(setOf(LeadQuestion.SIZE, LeadQuestion.DATE), target.reviewed?.missingQuestions)
        val first = repository.holdStaffQuestion(request, LeadQuestion.SIZE) as StaffQuestionResult.Held
        assertEquals(first, repository.holdStaffQuestion(request, LeadQuestion.SIZE))
        assertEquals(StaffQuestionResult.ConflictingHeld, repository.holdStaffQuestion(request, LeadQuestion.DATE))
        assertNull(repository.getSendClaim(first.draftId))
        reopen()
        val held = requireNotNull(repository.draft(first.draftId))
        assertEquals(DraftRendererPolicy.STAFF_QUESTION_V1, held.rendererPolicy)
        assertEquals("Gracias por escribirnos. ¿Qué tamaño tienes en mente?", held.body)
        assertTrue(held.canReview)
        assertTrue(repository.saveStaffEdit(held.draftId, "¿Qué tamaño prefieres?"))
        assertNotNull(repository.claimDraftForSend(held.draftId, held.body, "¿Qué tamaño prefieres?", 3))
        assertNull(repository.claimDraftForSend(held.draftId, held.body, held.body, 3))
        assertNull(repository.staffQuestionTarget(42))
    }

    @Test fun reviewAndConversationChangesInvalidateSelectionAndClaim() {
        val first = review()
        val staleChoice = first
        val corrected = repository.reviewLeadIntake(first, ReplyLanguage.EN,
            setOf(LeadQuestion.PLACEMENT)) as LeadIntakeReviewResult.Saved
        assertEquals(StaffQuestionResult.Stale, repository.holdStaffQuestion(staleChoice, LeadQuestion.SIZE))
        assertEquals(StaffQuestionResult.Stale, repository.holdStaffQuestion(corrected.request, LeadQuestion.SIZE))
        val id = (repository.holdStaffQuestion(corrected.request, LeadQuestion.PLACEMENT) as StaffQuestionResult.Held).draftId
        repository.setPaused(first.contactId, true)
        assertFalse(repository.draft(id)!!.canReview)
        repository.setPaused(first.contactId, false)
        assertNull(repository.claimDraftForSend(id, repository.draft(id)!!.body, repository.draft(id)!!.body, 3))
        assertFalse(repository.draft(id)!!.canReview)
        assertEquals(StaffQuestionResult.Stale, repository.holdStaffQuestion(corrected.request, LeadQuestion.PLACEMENT))
        val second = inbound(eventId = "sms:2", receivedAt = 2_000)
        assertEquals(second.eventId, repository.staffQuestionTarget(42)?.request?.eventId)
        assertEquals(StaffQuestionResult.Stale, repository.holdStaffQuestion(corrected.request, LeadQuestion.PLACEMENT))
    }

    @Test fun legacyRawCannotClaimButNewStaffPolicyCanCoexistWithoutUpgradingIt() {
        val original = inbound()
        repository.database.execSQL("""INSERT INTO held_drafts(draft_id,event_id,kind,thread_id,contact_id,
            recipient,source_revision,body,renderer_policy,created_at)
            VALUES('raw',?,'CUSTOMER_SERVICE_REPLY',?,?,?,?,'Old unsafe text','LEGACY_RAW',1000)""",
            arrayOf<Any>(original.eventId, original.threadId, original.contactId, "+15551234567", original.expectedRevision))
        val reviewed = (repository.reviewLeadIntake(original, ReplyLanguage.FR,
            setOf(LeadQuestion.REFERENCE)) as LeadIntakeReviewResult.Saved).request
        val id = (repository.holdStaffQuestion(reviewed, LeadQuestion.REFERENCE) as StaffQuestionResult.Held).draftId
        assertEquals(DraftRendererPolicy.LEGACY_RAW, repository.draft("raw")?.rendererPolicy)
        assertFalse(repository.draft("raw")!!.canReview)
        assertNull(repository.claimDraftForSend("raw", "Old unsafe text", "Old unsafe text", 3))
        repository.database.execSQL("UPDATE held_drafts SET staff_question_policy='STAFF_QUESTION_V1' WHERE draft_id='raw'")
        assertFalse(repository.draft("raw")!!.canReview) // The kind must match as well as the marker.
        assertNull(repository.claimDraftForSend("raw", "Old unsafe text", "Old unsafe text", 3))
        assertTrue(repository.draft(id)!!.canReview)
    }

    @Test fun staffTakeoverInvalidatesTheHeldQuestionWithoutARevisionChange() {
        val request = review(setOf(LeadQuestion.STYLE))
        val id = (repository.holdStaffQuestion(request, LeadQuestion.STYLE) as StaffQuestionResult.Held).draftId
        repository.database.recordStudioStaffAttention(request.eventId, request.expectedRevision, "STAFF_REPLY")
        assertTrue(repository.markStaffAttentionHandled(request.eventId, request.expectedRevision))
        val draft = requireNotNull(repository.draft(id))
        assertFalse(draft.canReview)
        assertNull(repository.claimDraftForSend(id, draft.body, draft.body, 3))
        assertEquals(StaffQuestionResult.Stale, repository.holdStaffQuestion(request, LeadQuestion.STYLE))
    }

    @Test fun dismissedChoiceCanBeReplacedAfterRestartWithoutReusingHistoryOrClaimedRows() {
        repository.setDraftingEnabled(false)
        val request = review()
        val first = (repository.holdStaffQuestion(request, LeadQuestion.SIZE) as StaffQuestionResult.Held).draftId
        assertTrue(repository.dismissDraft(first))
        reopen()
        val replacement = (repository.holdStaffQuestion(request, LeadQuestion.DATE) as StaffQuestionResult.Held).draftId
        assertFalse(first == replacement)
        val target = requireNotNull(repository.staffQuestionTarget(42))
        assertEquals(request.expectedRevision + 1, target.request.expectedRevision)
        assertEquals(setOf(LeadQuestion.SIZE, LeadQuestion.DATE), target.reviewed?.missingQuestions)
        assertEquals("DISMISSED", repository.database.rawQuery("SELECT state FROM held_drafts WHERE draft_id=?",
            arrayOf(first)).use { it.moveToFirst(); it.getString(0) })
        assertEquals(StaffQuestionResult.Held(replacement),
            repository.holdStaffQuestion(target.request, LeadQuestion.DATE))
        val held = requireNotNull(repository.draft(replacement))
        assertTrue(held.canReview)
        assertNotNull(repository.claimDraftForSend(replacement, held.body, held.body, 3))
        assertEquals(StaffQuestionResult.Stale, repository.holdStaffQuestion(target.request, LeadQuestion.SIZE))
        assertNull(repository.claimDraftForSend(first, "Gracias por escribirnos. ¿Qué tamaño tienes en mente?",
            "Reopen", 3))
    }

    @Test fun concurrentSelectionHasExactlyOneDurableQuestion() {
        val request = review(setOf(LeadQuestion.SIZE))
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val outcomes = mutableListOf<StaffQuestionResult>()
        val lock = Any()
        repeat(2) {
            thread {
                try {
                    StudioRepository(context, databaseName).use { other ->
                        start.await(5, TimeUnit.SECONDS)
                        val result = other.holdStaffQuestion(request, LeadQuestion.SIZE)
                        synchronized(lock) { outcomes.add(result) }
                    }
                } finally { finished.countDown() }
            }
        }
        start.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertEquals(2, outcomes.size)
        assertEquals(1, outcomes.map { (it as StaffQuestionResult.Held).draftId }.toSet().size)
        assertEquals(1, repository.heldDrafts().count { it.rendererPolicy == DraftRendererPolicy.STAFF_QUESTION_V1 })
    }
}
