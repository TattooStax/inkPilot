package inkpilot.inference

import inkpilot.agent.DraftCancellation

internal object NativeInference {
    init { System.loadLibrary("inkpilot-inference") }

    external fun generate(
        modelPath: ByteArray,
        instructions: ByteArray,
        userData: ByteArray,
        contextTokens: Int,
        maximumOutputTokens: Int,
        maximumReplyCharacters: Int,
        deadlineMillis: Long,
        threads: Int,
        cancellation: DraftCancellation,
    ): ByteArray
}
