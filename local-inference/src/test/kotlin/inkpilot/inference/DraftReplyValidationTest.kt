package inkpilot.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftReplyValidationTest {
    @Test(expected = IllegalStateException::class)
    fun leakedToolParameterClosingTagIsRejected() {
        DraftReplyValidation.finalText("What day works?</parameter>", 800)
    }

    @Test
    fun ordinaryHeartTextIsKept() {
        assertEquals("Thanks for the <3 reference!", DraftReplyValidation.finalText(" Thanks for the <3 reference! ", 800))
    }

    @Test(expected = IllegalStateException::class)
    fun blankFinalReplyIsRejected() {
        DraftReplyValidation.finalText(" \n\t ", 800)
    }

    @Test(expected = IllegalStateException::class)
    fun supplementaryCharactersStillCountAsTwoUtf16Units() {
        DraftReplyValidation.finalText("\uD83D\uDE00\uD83D\uDE00", 3)
    }
}
