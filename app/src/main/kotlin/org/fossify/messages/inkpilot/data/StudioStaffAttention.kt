package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** One visible staff task per thread after local drafting fails or is blocked. */
data class StudioStaffAttention(
    val eventId: String,
    val threadId: Long,
    val recipient: String,
    val displayName: String,
    val inboundText: String,
    val reason: String,
    val sourceRevision: Long,
    val receivedAt: Long,
    val current: Boolean,
)

private const val DEFAULT_ATTENTION_LIMIT = 100
private const val MAX_ATTENTION_RESULTS = 500
private const val EVENT_ID = 0
private const val THREAD_ID = 1
private const val RECIPIENT = 2
private const val DISPLAY_NAME = 3
private const val INBOUND_TEXT = 4
private const val REASON = 5
private const val SOURCE_REVISION = 6
private const val RECEIVED_AT = 7
private const val CURRENT = 8

internal object StudioStaffAttentionSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE staff_attention (
                attention_id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL REFERENCES inbound_events(event_id),
                source_revision INTEGER NOT NULL,
                reason TEXT NOT NULL,
                resolved_at INTEGER,
                resolution TEXT,
                UNIQUE(event_id, source_revision),
                CHECK ((resolved_at IS NULL AND resolution IS NULL) OR
                       (resolved_at IS NOT NULL AND resolution IN ('STAFF_HANDLED', 'REPLACED')))
            )
        """.trimIndent())
        // Migrate only failures without a later held reply for that inbound event.
        db.execSQL("""
            INSERT INTO staff_attention(event_id, source_revision, reason)
            SELECT a.event_id, a.source_revision, a.reason
            FROM draft_attempts a JOIN inbound_events e ON e.event_id = a.event_id
            WHERE NOT EXISTS (
                SELECT 1 FROM held_drafts h WHERE h.event_id = a.event_id AND h.source_revision >= a.source_revision
            ) AND NOT EXISTS (
                SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id = s.draft_id
                WHERE h.event_id = a.event_id
            )
            ORDER BY e.rowid, a.source_revision
        """.trimIndent())
    }
}

fun StudioRepository.staffAttention(limit: Int = DEFAULT_ATTENTION_LIMIT): List<StudioStaffAttention> =
    database.studioStaffAttention(limit)

fun StudioRepository.markStaffAttentionHandled(eventId: String, sourceRevision: Long): Boolean =
    database.markThreadStaffAttentionHandled(eventId, sourceRevision)

internal fun SQLiteDatabase.recordStudioStaffAttention(eventId: String, sourceRevision: Long, reason: String) {
    insertWithOnConflict("staff_attention", null, ContentValues().apply {
        put("event_id", eventId)
        put("source_revision", sourceRevision)
        put("reason", reason)
    }, SQLiteDatabase.CONFLICT_IGNORE)
}

/** A held reply supersedes failures for this inbound event, never another client question. */
internal fun SQLiteDatabase.resolveStaffAttentionForEvent(eventId: String, sourceRevision: Long) {
    execSQL("""
        UPDATE staff_attention SET resolved_at = ?, resolution = 'REPLACED'
        WHERE resolved_at IS NULL AND event_id = ? AND source_revision <= ?
    """.trimIndent(), arrayOf<Any>(System.currentTimeMillis(), eventId, sourceRevision))
}

/** Handling a thread closes older tasks but cannot hide a newer arrival racing the click. */
internal fun SQLiteDatabase.markThreadStaffAttentionHandled(
    eventId: String, sourceRevision: Long,
): Boolean = transaction {
    val target = rawQuery("""
        SELECT e.thread_id, e.rowid FROM staff_attention a
        JOIN inbound_events e ON e.event_id = a.event_id
        WHERE a.event_id = ? AND a.source_revision = ? AND a.resolved_at IS NULL
    """.trimIndent(), arrayOf(eventId, sourceRevision.toString())).use { cursor ->
        if (!cursor.moveToFirst()) return@transaction false
        cursor.getString(0) to cursor.getLong(1)
    }
    execSQL("""
        UPDATE staff_attention SET resolved_at = ?, resolution = 'STAFF_HANDLED'
        WHERE resolved_at IS NULL AND attention_id IN (
            SELECT a.attention_id FROM staff_attention a
            JOIN inbound_events e ON e.event_id = a.event_id
            WHERE e.thread_id = ? AND (e.rowid < ? OR (e.rowid = ? AND a.source_revision <= ?))
        )
    """.trimIndent(), arrayOf<Any>(System.currentTimeMillis(), target.first, target.second,
        target.second, sourceRevision))
    true
}

internal fun SQLiteDatabase.staffHandledSource(eventId: String): Boolean =
    rawQuery(
        "SELECT 1 FROM staff_attention WHERE event_id = ? AND resolution = 'STAFF_HANDLED'",
        arrayOf(eventId),
    ).use { it.moveToFirst() }

internal fun SQLiteDatabase.studioStaffAttention(limit: Int): List<StudioStaffAttention> {
    require(limit in 1..MAX_ATTENTION_RESULTS)
    // Inbound rowid is intake order; provider timestamps and event IDs can tie or arrive out of order.
    return rawQuery(
        """
            SELECT a.event_id, e.thread_id, e.recipient, c.display_name, e.body, a.reason,
                   a.source_revision, e.received_at,
                   (c.latest_event_id = a.event_id AND c.revision = a.source_revision)
            FROM staff_attention a
            JOIN inbound_events e ON e.event_id = a.event_id
            JOIN studio_contacts c ON c.id = e.contact_id
            WHERE a.resolved_at IS NULL AND NOT EXISTS (
                SELECT 1 FROM staff_attention newer
                JOIN inbound_events later ON later.event_id = newer.event_id
                WHERE newer.resolved_at IS NULL AND later.thread_id = e.thread_id AND (
                    later.rowid > e.rowid OR
                    (later.rowid = e.rowid AND newer.source_revision > a.source_revision)
                )
            )
            ORDER BY e.rowid DESC, a.source_revision DESC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(StudioStaffAttention(
                cursor.getString(EVENT_ID), cursor.getString(THREAD_ID).toLong(), cursor.getString(RECIPIENT),
                cursor.getString(DISPLAY_NAME), cursor.getString(INBOUND_TEXT), cursor.getString(REASON),
                cursor.getLong(SOURCE_REVISION), cursor.getLong(RECEIVED_AT), cursor.getInt(CURRENT) != 0,
            ))
        }
    }
}
