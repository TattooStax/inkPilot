package org.fossify.messages.inkpilot.runtime

import inkpilot.agent.*
import org.fossify.messages.inkpilot.data.StudioRepository

/** Read-only views of the existing authoritative store. No send, write or provider access.
 * Album IDs are explicitly granted by the host; no album is exposed by default.
 */
internal class StudioToolReads(
    private val repository: StudioRepository,
    private val scope: DraftRequest,
    private val allowedAlbumIds: Set<Long> = emptySet(),
) : LocalToolReadPorts {
    override fun current(request: DraftRequest): Boolean {
        if (request != scope) return false
        val source = repository.readSource(scope.eventId) ?: return false
        return source.eventId == scope.eventId && source.contactId == scope.contactId && source.threadId == scope.threadId &&
            source.revision == scope.expectedRevision && !source.staffPaused
    }

    override fun contact(contactId: String): List<ToolRecord> {
        require(contactId == scope.contactId)
        val contact = repository.contact(contactId) ?: return emptyList()
        // Phone numbers and recipient routing are deliberately absent.
        return listOf(record("studio_contact:${contact.id}", contact.displayName.take(160) + "\n" + contact.memory.take(1800)))
    }

    override fun thread(contactId: String, threadId: String, limit: Int): List<ToolRecord> {
        require(contactId == scope.contactId && threadId == scope.threadId && limit in 1..20)
        return repository.database.rawQuery(
            "SELECT event_id,substr(body,1,1900) FROM inbound_events WHERE contact_id=? AND thread_id=? ORDER BY received_at DESC,event_id DESC LIMIT ?",
            arrayOf(contactId, threadId, limit.toString()),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val event = cursor.getString(0)
                // Intake history is a partial inbound view, not a complete provider conversation.
                add(record("inbound_event:$event", "Partial inbound history: " + cursor.getString(1),
                    event.startsWith("mms", ignoreCase = true)))
            }
        } }
    }

    override fun availability(start: Long, end: Long, limit: Int): List<ToolRecord> {
        require(start >= 0 && end > start && end - start <= 7 * 86_400_000L && limit in 1..20)
        return repository.database.rawQuery(
            "SELECT id,staff_id,starts_at,ends_at FROM studio_calendar WHERE status='scheduled' AND starts_at<? AND ends_at>? ORDER BY starts_at,id LIMIT ?",
            arrayOf(end.toString(), start.toString(), limit.toString()),
        ).use { cursor -> buildList {
            // Do not disclose other customers' titles. Absence is never a claim of availability.
            add(record("studio_calendar:coverage", "Local occupied intervals only; working hours and external bookings are unknown. No free slot is confirmed."))
            while (cursor.moveToNext()) add(record("studio_calendar:${cursor.getLong(0)}",
                "staff=${cursor.getLong(1)} occupied ${cursor.getLong(2)}..${cursor.getLong(3)}"))
        } }
    }

    override fun albums(limit: Int): List<ToolRecord> {
        require(limit in 1..20)
        if (allowedAlbumIds.isEmpty()) return emptyList()
        val ids = allowedAlbumIds.sorted().take(20)
        return repository.database.rawQuery(
            "SELECT a.id,substr(a.name,1,80),i.id FROM studio_albums a JOIN studio_album_images m ON a.id=m.album_id JOIN studio_images i ON i.id=m.image_id WHERE a.id IN (${ids.joinToString(",") { "?" }}) ORDER BY a.id,i.id LIMIT ?",
            (ids.map { it.toString() } + limit.toString()).toTypedArray(),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(record("studio_album:${cursor.getLong(0)}/image:${cursor.getLong(2)}",
                "Album reference: ${cursor.getString(1)}. Image content has not been seen.", true))
        } }
    }

    private fun record(source: String, text: String, image: Boolean = false) =
        ToolRecord(scope.contactId, scope.threadId, source, text, image)
}

/** Available to host orchestration; Task16 syntax-only qualification does not enable model dispatch. */
internal fun localReadTools(repository: StudioRepository, request: DraftRequest): LocalToolRegistry =
    LocalToolRegistry(request, StudioToolReads(repository, request))
