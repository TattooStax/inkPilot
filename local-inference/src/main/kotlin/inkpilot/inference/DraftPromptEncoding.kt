package inkpilot.inference

import inkpilot.agent.DraftPrompt
import inkpilot.agent.ToolResult

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

    /** Tool data is encoded as untrusted user data, never appended to system instructions. */
    fun toolData(result: ToolResult.Read): String = buildString {
        append("{\"untrusted_local_tool_data\":{\"tool\":")
        quoted(result.toolName)
        append(",\"version\":${result.version},\"source_revision\":${result.sourceRevision}")
        append(",\"truncated\":${result.truncated},\"records\":[")
        result.records.forEachIndexed { index, record ->
            if (index > 0) append(',')
            append("{\"source\":")
            quoted(record.sourceId)
            append(",\"text\":")
            quoted(record.text)
            append(",\"image_knowledge\":")
            quoted(record.imageKnowledge.name)
            append('}')
        }
        append("]}}")
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
