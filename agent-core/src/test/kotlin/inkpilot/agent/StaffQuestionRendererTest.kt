package inkpilot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StaffQuestionRendererTest {
    @Test fun everyStaffChoiceHasAClosedLocalizedQuestion() {
        for (language in ReplyLanguage.entries) for (question in LeadQuestion.entries) {
            val first = BoundedIntentReplyRenderer.staffQuestion(language, question)
            assertFalse(first.isBlank())
            assertEquals(first, BoundedIntentReplyRenderer.staffQuestion(language, question))
            assertFalse(first.contains("LANGUAGE:"))
        }
        assertEquals("Thanks for reaching out. What size are you thinking?",
            BoundedIntentReplyRenderer.staffQuestion(ReplyLanguage.EN, LeadQuestion.SIZE))
    }
}
