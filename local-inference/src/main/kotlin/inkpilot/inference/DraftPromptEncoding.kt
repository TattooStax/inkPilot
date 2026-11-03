package inkpilot.inference

import inkpilot.agent.DraftPrompt

/** The entire customer payload is a user message; it never becomes template syntax. */
internal object DraftPromptEncoding {
    fun userData(prompt: DraftPrompt): String = buildString {
        append("{\"customer_message\":")
        quoted(prompt.inboundText)
        append(",\"staff_approved_contact_notes\":[")
        prompt.contactMemory.forEachIndexed { index, note ->
            if (index > 0) append(',')
            quoted(note)
        }
        append("]}")
    }

    private fun StringBuilder.quoted(text: String) {
        append('"')
        text.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }
}
