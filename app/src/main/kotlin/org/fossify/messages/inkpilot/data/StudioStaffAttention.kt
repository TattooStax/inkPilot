package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** One visible staff task per thread after a local draft fails or is blocked. */
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

fun StudioRepository.staffAttention(limit: Int = DEFAULT_ATTENTION_LIMIT): List<StudioStaffAttention> =
    database.studioStaffAttention(limit)

internal fun SQLiteDatabase.studioStaffAttention(limit: Int): List<StudioStaffAttention> {
    require(limit in 1..MAX_ATTENTION_RESULTS)
    // Keep a failed attempt visible when later messages or memory edits change the source.
    // For one thread, show its latest attempted event/revision rather than repeated tasks.
    return rawQuery(
        """
            SELECT a.event_id, e.thread_id, e.recipient, c.display_name, e.body, a.reason,
                   a.source_revision, e.received_at,
                   (c.latest_event_id = a.event_id AND c.revision = a.source_revision)
            FROM draft_attempts a
            JOIN inbound_events e ON e.event_id = a.event_id
            JOIN studio_contacts c ON c.id = e.contact_id
            WHERE NOT EXISTS (
                SELECT 1 FROM draft_attempts newer
                JOIN inbound_events later ON later.event_id = newer.event_id
                WHERE later.thread_id = e.thread_id AND (
                    later.received_at > e.received_at OR
                    (later.received_at = e.received_at AND later.event_id > e.event_id) OR
                    (later.event_id = e.event_id AND newer.source_revision > a.source_revision)
                )
            )
            ORDER BY e.received_at DESC, a.event_id DESC LIMIT ?
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
