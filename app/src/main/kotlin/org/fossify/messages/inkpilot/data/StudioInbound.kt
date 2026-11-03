package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.DraftKind
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftSource
import java.util.UUID

internal fun SQLiteDatabase.recordStudioInbound(
    eventId: String, threadId: Long, number: String, body: String, displayName: String,
    receivedAt: Long, subscriptionId: Int,
): DraftRequest {
    require(eventId.isNotBlank() && eventId.length <= 256 && threadId > 0 && receivedAt >= 0)
    require(body.isNotBlank() && body.length <= 16_000 && displayName.length <= 256)
    val normalized = canonicalStudioNumber(number)
    return transaction {
        rawQuery(
            "SELECT thread_id, contact_id, recipient, body, created_revision FROM inbound_events WHERE event_id = ?",
            arrayOf(eventId),
        ).use { old ->
            if (old.moveToFirst()) {
                require(old.getString(0) == threadId.toString() && old.getString(2) == normalized && old.getString(3) == body) {
                    "Inbound event identity was reused with different content"
                }
                return@transaction DraftRequest(eventId, old.getString(0), old.getString(1), old.getLong(4))
            }
        }
        val contact = contactByNumber(normalized) ?: run {
            val id = UUID.randomUUID().toString()
            insertOrThrow("studio_contacts", null, ContentValues().apply {
                put("id", id); put("number", normalized)
                put("display_name", displayName.trim().ifEmpty { normalized })
            })
            checkNotNull(studioContact(id))
        }
        val isLatest = contact.latestEventId == null || receivedAt >= contact.lastMessageAt
        val revision = contact.revision + if (isLatest) 1 else 0
        insertOrThrow("inbound_events", null, ContentValues().apply {
            put("event_id", eventId); put("thread_id", threadId.toString()); put("contact_id", contact.id)
            put("recipient", normalized); put("body", body); put("received_at", receivedAt)
            put("subscription_id", subscriptionId); put("created_revision", revision)
        })
        if (isLatest) {
            execSQL(
                "UPDATE studio_contacts SET revision = ?, latest_event_id = ?, last_message_at = ? WHERE id = ?",
                arrayOf<Any>(revision, eventId, receivedAt, contact.id),
            )
        }
        DraftRequest(eventId, threadId.toString(), contact.id, revision)
    }
}

internal fun SQLiteDatabase.studioSource(eventId: String): DraftSource? = rawQuery(
    """
        SELECT e.event_id, e.thread_id, e.contact_id, c.number, c.revision, e.body,
               (c.paused = 1 OR COALESCE((SELECT drafting_enabled FROM agent_settings WHERE id = 1), 0) = 0)
        FROM inbound_events e JOIN studio_contacts c ON c.id = e.contact_id
        WHERE e.event_id = ? AND c.latest_event_id = e.event_id AND c.number = e.recipient
    """.trimIndent(), arrayOf(eventId),
).use {
    if (!it.moveToFirst()) null else DraftSource(
        it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getString(5), it.getInt(6) != 0,
    )
}

internal fun SQLiteDatabase.pendingStudioRequests(limit: Int): List<DraftRequest> {
    require(limit in 1..500)
    return rawQuery(
        """
            SELECT e.event_id, e.thread_id, e.contact_id, c.revision
            FROM studio_contacts c JOIN inbound_events e ON e.event_id = c.latest_event_id
            WHERE c.paused = 0 AND (SELECT drafting_enabled FROM agent_settings WHERE id = 1) = 1 AND NOT EXISTS (
                SELECT 1 FROM held_drafts d WHERE d.event_id = e.event_id AND d.kind = ?
            ) AND NOT EXISTS (
                SELECT 1 FROM draft_attempts a WHERE a.event_id = e.event_id AND a.source_revision = c.revision
            ) ORDER BY e.received_at ASC, e.event_id LIMIT ?
        """.trimIndent(), arrayOf(DraftKind.CUSTOMER_SERVICE_REPLY.name, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(DraftRequest(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }
    }
}
