package inkpilot.agent

/** Closed typed schemas: no recipient, executable code, URL, path or mutation argument. */
enum class ToolInputSchema { CONTACT, THREAD, AVAILABILITY, ALBUM_REFERENCES }
enum class ToolScope { HOST_CONTACT_AND_THREAD }
data class ToolDescriptor(val name: String, val version: Int, val inputSchema: ToolInputSchema, val scope: ToolScope)
sealed interface ToolInput {
    data object Contact : ToolInput
    data class Thread(val limit: Int = 10) : ToolInput
    data class Availability(val startsAt: Long, val endsAt: Long, val limit: Int = 10) : ToolInput
    data class AlbumReferences(val limit: Int = 10) : ToolInput
}
data class ScopedToolRequest(
    val name: String, val version: Int, val contactId: String, val threadId: String, val input: ToolInput,
)
enum class ImageKnowledge { NO_IMAGE, UNSEEN }
/** Port data is untrusted, including its scope and provenance. No filesystem paths are exposed. */
data class ToolRecord(
    val contactId: String, val threadId: String, val sourceId: String, val text: String,
    val hasImage: Boolean = false,
)
data class ToolDatum(val sourceId: String, val text: String, val imageKnowledge: ImageKnowledge)
enum class ToolFailure { UNKNOWN_TOOL, UNSUPPORTED_VERSION, INVALID_INPUT, CROSS_SCOPE, SOURCE_CHANGED, READ_FAILED }
sealed interface ToolResult {
    data class Rejected(val reason: ToolFailure) : ToolResult
    data class Read(
        val toolName: String, val version: Int, val contactId: String, val threadId: String,
        val sourceRevision: Long, val records: List<ToolDatum>, val truncated: Boolean,
    ) : ToolResult
}

/** Host-only read ports. Implementations apply the supplied limits in the underlying query. */
interface LocalToolReadPorts {
    fun current(request: DraftRequest): Boolean
    fun contact(contactId: String): List<ToolRecord>
    fun thread(contactId: String, threadId: String, limit: Int): List<ToolRecord>
    fun availability(start: Long, end: Long, limit: Int): List<ToolRecord>
    fun albums(limit: Int): List<ToolRecord>
}
