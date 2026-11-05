package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.BoundedIntentReplyRenderer
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioLeadIntakeTest : StudioDatabaseTestBase() {
    @Test fun reviewIsDurableAndExactForThisMessageOnly() {
        val original = inbound()
        assertFalse(BoundedIntentReplyRenderer.eligible(repository.readEvidence(original)))
        val saved = repository.reviewLeadIntake(original, ReplyLanguage.ES,
            setOf(LeadQuestion.STYLE, LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved
        assertEquals(original.expectedRevision + 1, saved.request.expectedRevision)
        val packet = requireNotNull(repository.readEvidence(saved.request))
        val scope = requireNotNull(packet.reviewedLeadIntake)
        assertEquals(original.eventId, scope.sourceId)
        assertEquals(original.threadId, scope.threadId)
        assertEquals(original.contactId, scope.contactId)
        assertEquals(saved.request.expectedRevision, scope.sourceRevision)
        assertEquals(ReplyLanguage.ES, scope.language)
        assertEquals(setOf(LeadQuestion.STYLE, LeadQuestion.SIZE), scope.missingQuestions)
        assertTrue(BoundedIntentReplyRenderer.eligible(packet))
        assertNull(BoundedIntentReplyRenderer.render("ES:DATE", packet))
        assertTrue(BoundedIntentReplyRenderer.render("ES:SIZE", packet)!!.contains("tamaño"))
        reopen()
        assertEquals(scope, repository.readEvidence(saved.request)?.reviewedLeadIntake)
        assertEquals(saved.request, repository.leadIntakeTargets().single().request)
    }

    @Test fun correctionBumpsRevisionAndInvalidatesAnInFlightDraft() {
        val original = inbound()
        val first = repository.reviewLeadIntake(original, ReplyLanguage.EN,
            setOf(LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved
        val pendingWrite = write(first.request)
        val corrected = repository.reviewLeadIntake(first.request, ReplyLanguage.FR,
            setOf(LeadQuestion.PLACEMENT)) as LeadIntakeReviewResult.Saved
        assertEquals(first.request.expectedRevision + 1, corrected.request.expectedRevision)
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(pendingWrite))
        assertFalse(BoundedIntentReplyRenderer.eligible(repository.readEvidence(first.request)))
        assertEquals(ReplyLanguage.FR, repository.readEvidence(corrected.request)?.reviewedLeadIntake?.language)
        assertTrue(repository.holdIfCurrent(write(corrected.request)) is DraftStoreResult.Held)
        assertTrue(repository.heldDrafts().single().canReview)
        val repeated = repository.reviewLeadIntake(corrected.request, ReplyLanguage.FR,
            setOf(LeadQuestion.PLACEMENT)) as LeadIntakeReviewResult.Saved
        assertEquals(corrected.request, repeated.request)
    }

    @Test fun sourceMemoryAndNewMessageChangesMakeScopeIneligible() {
        val original = inbound()
        val reviewed = repository.reviewLeadIntake(original, ReplyLanguage.EN,
            setOf(LeadQuestion.REFERENCE)) as LeadIntakeReviewResult.Saved
        repository.saveMemory(original.contactId, "New staff note")
        val memoryRevision = reviewed.request.copy(expectedRevision = repository.contact(original.contactId)!!.revision)
        assertNull(repository.readEvidence(memoryRevision)?.reviewedLeadIntake)
        assertFalse(BoundedIntentReplyRenderer.eligible(repository.readEvidence(memoryRevision)))
        assertEquals(LeadIntakeReviewResult.Stale, repository.reviewLeadIntake(reviewed.request,
            ReplyLanguage.EN, setOf(LeadQuestion.REFERENCE)))
        inbound(eventId = "sms:2", receivedAt = 2_000)
        assertNull(repository.readEvidence(memoryRevision))
        assertEquals(LeadIntakeReviewResult.Stale, repository.clearLeadIntake(memoryRevision))
    }

    @Test fun clearingScopeBumpsRevisionAndRejectsStaleCorrection() {
        val original = inbound()
        val reviewed = repository.reviewLeadIntake(original, ReplyLanguage.EN,
            setOf(LeadQuestion.DATE)) as LeadIntakeReviewResult.Saved
        val cleared = repository.clearLeadIntake(reviewed.request) as LeadIntakeReviewResult.Saved
        assertEquals(reviewed.request.expectedRevision + 1, cleared.request.expectedRevision)
        assertNull(repository.readEvidence(cleared.request)?.reviewedLeadIntake)
        assertEquals(LeadIntakeReviewResult.Stale, repository.clearLeadIntake(reviewed.request))
        assertEquals(cleared.request, (repository.clearLeadIntake(cleared.request) as LeadIntakeReviewResult.Saved).request)
    }

    @Test fun tamperedStoredThreadCannotBecomeModelEvidence() {
        val original = inbound()
        val reviewed = repository.reviewLeadIntake(original, ReplyLanguage.EN,
            setOf(LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved
        repository.database.execSQL("UPDATE lead_intake_reviews SET thread_id = 'different-thread' WHERE event_id = ?",
            arrayOf(original.eventId))
        assertNull(repository.readEvidence(reviewed.request)?.reviewedLeadIntake)
        assertFalse(BoundedIntentReplyRenderer.eligible(repository.readEvidence(reviewed.request)))
    }

    @Test fun aClaimedSendCannotAcquireFreshLeadAuthority() {
        val original = inbound()
        val draft = held(original)
        checkNotNull(repository.claimDraftForSend(draft.draftId, draft.body, draft.body, 3))
        assertEquals(LeadIntakeReviewResult.Stale, repository.reviewLeadIntake(original,
            ReplyLanguage.EN, setOf(LeadQuestion.SIZE)))
    }

    @Test fun crossContactAndHandledEventsCannotAcquireReviewAuthority() {
        val first = inbound()
        val other = inbound(eventId = "sms:other", number = "+15557654321")
        assertEquals(LeadIntakeReviewResult.Stale, repository.reviewLeadIntake(first.copy(contactId = other.contactId),
            ReplyLanguage.EN, setOf(LeadQuestion.STYLE)))
        repository.database.recordStudioStaffAttention(first.eventId, first.expectedRevision, "STAFF_REPLY")
        assertTrue(repository.markStaffAttentionHandled(first.eventId, first.expectedRevision))
        assertEquals(LeadIntakeReviewResult.Stale, repository.reviewLeadIntake(first,
            ReplyLanguage.EN, setOf(LeadQuestion.STYLE)))
    }
}
