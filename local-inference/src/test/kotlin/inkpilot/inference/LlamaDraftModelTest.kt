package inkpilot.inference

import inkpilot.agent.DraftCancellation
import inkpilot.agent.DraftPrompt
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Test

class LlamaDraftModelTest {
    private val prompt = DraftPrompt("Draft one reply.", "Hello", emptyList(), 800)

    @Test(expected = IllegalArgumentException::class)
    fun `missing weights fail before loading native code`() {
        LlamaDraftModel(File("missing-${System.nanoTime()}.gguf")).generate(prompt, DraftCancellation.NONE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non GGUF input never reaches native loader`() {
        val invalid = File.createTempFile("invalid-model", ".gguf")
        try {
            invalid.writeText("not model weights")
            LlamaDraftModel(invalid).generate(prompt, DraftCancellation.NONE)
        } finally {
            invalid.delete()
        }
    }

    @Test(expected = CancellationException::class)
    fun `cancelled request never loads the native library`() {
        val headerOnly = File.createTempFile("header-only", ".gguf")
        try {
            headerOnly.writeText("GGUF")
            LlamaDraftModel(headerOnly).generate(prompt, DraftCancellation { true })
        } finally {
            headerOnly.delete()
        }
    }
}
