package inkpilot.inference

/** Validate final customer text independently of JNI or a model implementation. */
internal object DraftReplyValidation {
    private val controlMarkers = listOf(
        "<think>", "</think>", "<|im_start|>", "<|im_end|>",
        "<tool_call>", "</tool_call>", "<function=", "</function>", "<parameter=", "</parameter>",
    )

    fun finalText(text: String, maximumCharacters: Int): String {
        val output = text.trim()
        check(output.isNotEmpty() && output.length <= maximumCharacters) {
            "The local model did not produce a complete reply within its limits."
        }
        check(controlMarkers.none { output.contains(it) }) {
            "The local model returned internal control text instead of a reply."
        }
        return output
    }
}
