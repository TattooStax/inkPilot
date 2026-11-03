package inkpilot.agent

/** A host-issued trigger. The host, never the model, selects this scope. */
data class DraftRequest(
    val eventId: String,
    val threadId: String,
    val contactId: String,
    val expectedRevision: Long,
) {
    init {
        require(eventId.isNotBlank() && threadId.isNotBlank() && contactId.isNotBlank())
        require(expectedRevision >= 0)
    }
}

/** Authoritative state, including the current staff pause and source message. */
data class DraftSource(
    val eventId: String,
    val threadId: String,
    val contactId: String,
    val recipient: String,
    val revision: Long,
    val inboundText: String,
    val staffPaused: Boolean,
)

data class ContactMemory(val contactId: String, val text: String)

fun interface ContactMemoryReader {
    /** Return only staff-approved memory belonging to this contact. */
    fun read(contactId: String): List<ContactMemory>
}

/** Limits count UTF-16 code units, not SMS segments or model tokens. */
data class DraftLimits(
    val inboundCharacters: Int = 4_000,
    val memoryEntries: Int = 12,
    val memoryCharacters: Int = 8_000,
    val replyCharacters: Int = 800,
) {
    init {
        require(inboundCharacters > 0 && memoryEntries >= 0)
        require(memoryCharacters >= 0 && replyCharacters > 0)
    }
}

fun interface DraftCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = DraftCancellation { false }
    }
}
