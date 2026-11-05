package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioLeadIntakeMigrationTest : StudioDatabaseTestBase() {
    @Test fun v7UpgradeKeepsHeldDraftsAndCreatesReviewTable() {
        val request = inbound()
        val existing = held(request)
        repository.database.execSQL("DROP TABLE lead_intake_reviews")
        repository.database.version = 7
        reopen()
        assertEquals(8, repository.database.version)
        assertEquals(existing.body, repository.draft(existing.draftId)?.body)
        assertNull(repository.readEvidence(request)?.reviewedLeadIntake)
        val saved = repository.reviewLeadIntake(request, ReplyLanguage.EN,
            setOf(LeadQuestion.SIZE)) as LeadIntakeReviewResult.Saved
        assertTrue(saved.request.expectedRevision > request.expectedRevision)
        assertEquals(setOf(LeadQuestion.SIZE), repository.readEvidence(saved.request)?.reviewedLeadIntake?.missingQuestions)
        assertEquals(false, repository.draft(existing.draftId)?.canReview)
    }
}
