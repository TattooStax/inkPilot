package inkpilot.inference

import inkpilot.agent.DraftCancellation
import inkpilot.agent.DraftPrompt
import inkpilot.agent.LocalDraftModel
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** JNI CPU inference from an app-private GGUF. No model/server download or network fallback. */
class LlamaDraftModel(
    private val modelFile: File,
    private val limits: InferenceLimits = InferenceLimits(),
) : LocalDraftModel {
    override fun generate(prompt: DraftPrompt, cancellation: DraftCancellation): String {
        require(prompt.instructions.length <= 8000 && prompt.inboundText.length <= 4000)
        require(prompt.contactMemory.size <= 12 && prompt.contactMemory.sumOf { it.length.toLong() } <= 8000)
        require(prompt.maximumReplyCharacters in 1..4000)
        require(modelFile.isFile && modelFile.length() in 4..2_000_000_000L) { "Import a compatible local GGUF model first." }
        modelFile.inputStream().use { input ->
            val magic = ByteArray(4)
            require(input.read(magic) == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))) {
                "The selected file is not a GGUF model."
            }
        }
        val started = System.nanoTime()
        while (!inferenceLock.tryLock(100, TimeUnit.MILLISECONDS)) {
            checkCancellation(cancellation)
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < limits.deadlineMillis) {
                "Local inference is busy. Try again when the current draft finishes."
            }
        }
        try {
            checkCancellation(cancellation)
            val output = NativeInference.generate(
                modelFile.absolutePath.toByteArray(Charsets.UTF_8),
                prompt.instructions.toByteArray(Charsets.UTF_8),
                DraftPromptEncoding.userData(prompt).toByteArray(Charsets.UTF_8),
                limits.contextTokens, limits.maximumOutputTokens, prompt.maximumReplyCharacters,
                limits.deadlineMillis, limits.threads, cancellation,
            ).toString(Charsets.UTF_8).trim()
            checkCancellation(cancellation)
            check(output.isNotEmpty() && output.length <= prompt.maximumReplyCharacters) {
                "The local model did not produce a complete reply within its limits."
            }
            check(listOf("<think>", "</think>", "<tool_call>", "<|im_start|>", "<|im_end|>")
                .none { output.contains(it) }) { "The local model returned internal control text instead of a reply." }
            return output
        } finally {
            // Native model, context, batch and sampler are freed before JNI returns.
            inferenceLock.unlock()
        }
    }

    private fun checkCancellation(cancellation: DraftCancellation) {
        if (Thread.currentThread().isInterrupted || cancellation.isCancelled()) throw CancellationException()
    }

    companion object {
        private val inferenceLock = ReentrantLock()
    }
}
