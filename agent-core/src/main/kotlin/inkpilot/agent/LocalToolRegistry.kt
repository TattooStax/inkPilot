package inkpilot.agent

/** Constructed per host-issued event. Registration is fixed; no model-controlled plugins. */
class LocalToolRegistry(private val scope: DraftRequest, private val ports: LocalToolReadPorts) {
    fun read(request: ScopedToolRequest): ToolResult {
        val descriptor = descriptors.find { it.name == request.name }
            ?: return reject(ToolFailure.UNKNOWN_TOOL)
        if (request.version != descriptor.version) return reject(ToolFailure.UNSUPPORTED_VERSION)
        if (request.contactId != scope.contactId || request.threadId != scope.threadId) return reject(ToolFailure.CROSS_SCOPE)
        if (!valid(descriptor.inputSchema, request.input)) return reject(ToolFailure.INVALID_INPUT)
        return try {
            if (!ports.current(scope)) return reject(ToolFailure.SOURCE_CHANGED)
            val rows = when (val input = request.input) {
                ToolInput.Contact -> ports.contact(scope.contactId)
                is ToolInput.Thread -> ports.thread(scope.contactId, scope.threadId, input.limit)
                is ToolInput.Availability -> ports.availability(input.startsAt, input.endsAt, input.limit)
                is ToolInput.AlbumReferences -> ports.albums(input.limit)
            }
            if (!ports.current(scope)) return reject(ToolFailure.SOURCE_CHANGED)
            // Even a buggy host adapter must not leak another contact's data.
            if (rows.any { it.contactId != scope.contactId || it.threadId != scope.threadId }) return reject(ToolFailure.CROSS_SCOPE)
            val limit = when (val input = request.input) {
                ToolInput.Contact -> 1
                is ToolInput.Thread -> input.limit
                is ToolInput.Availability -> input.limit
                is ToolInput.AlbumReferences -> input.limit
            }
            var budget = 8000
            var truncated = rows.size > limit
            val data = rows.take(limit).map { row ->
                val text = row.text.take(minOf(2000, budget))
                budget -= text.length
                truncated = truncated || text.length != row.text.length || row.sourceId.length > 120
                ToolDatum(row.sourceId.take(120), text,
                    if (row.hasImage) ImageKnowledge.UNSEEN else ImageKnowledge.NO_IMAGE)
            }
            ToolResult.Read(descriptor.name, descriptor.version, scope.contactId, scope.threadId,
                scope.expectedRevision, data, truncated)
        } catch (_: Exception) { reject(ToolFailure.READ_FAILED) }
    }

    private fun valid(schema: ToolInputSchema, input: ToolInput): Boolean = when (input) {
        ToolInput.Contact -> schema == ToolInputSchema.CONTACT
        is ToolInput.Thread -> schema == ToolInputSchema.THREAD && input.limit in 1..20
        is ToolInput.AlbumReferences -> schema == ToolInputSchema.ALBUM_REFERENCES && input.limit in 1..20
        is ToolInput.Availability -> schema == ToolInputSchema.AVAILABILITY && input.limit in 1..20 &&
            input.startsAt >= 0 && input.endsAt > input.startsAt && input.endsAt - input.startsAt <= 7 * 86_400_000L
    }
    private fun reject(reason: ToolFailure) = ToolResult.Rejected(reason)

    companion object {
        val descriptors: List<ToolDescriptor> = listOf(
            "read_contact" to ToolInputSchema.CONTACT, "read_thread" to ToolInputSchema.THREAD,
            "read_availability" to ToolInputSchema.AVAILABILITY, "read_album_references" to ToolInputSchema.ALBUM_REFERENCES,
        ).map { (name, schema) -> ToolDescriptor(name, 1, schema, ToolScope.HOST_CONTACT_AND_THREAD) }
    }
}
