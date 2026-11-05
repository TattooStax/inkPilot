package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** Preserve an actionable staff task when a reviewed contact uses multiple SMS threads. */
internal fun SQLiteDatabase.recordCrossThreadAttention(
    priorEvent: String?, priorRevision: Long, incomingEvent: String,
    incomingThread: Long, incomingRevision: Long, isLatest: Boolean,
) {
    val previous = priorEvent ?: return
    val priorThread = rawQuery("SELECT thread_id FROM inbound_events WHERE event_id = ?", arrayOf(previous)).use {
        check(it.moveToFirst()); it.getString(0)
    }
    if (priorThread == incomingThread.toString()) return
    val displaced = if (isLatest) previous else incomingEvent
    val revision = if (isLatest) priorRevision else incomingRevision
    if (!staffHandledSource(displaced) && !hasStudioSendClaim(displaced) && !hasOpenStaffAttention(displaced)) {
        recordStudioStaffAttention(displaced, revision, "OTHER_THREAD_RECEIVED")
    }
}

private fun SQLiteDatabase.hasStudioSendClaim(eventId: String): Boolean = rawQuery(
    "SELECT 1 FROM send_claims s JOIN held_drafts d ON d.draft_id=s.draft_id WHERE d.event_id=? LIMIT 1",
    arrayOf(eventId),
).use { it.moveToFirst() }

/** Reassigning a reviewed number invalidates its old owner's pending reply. */
internal fun SQLiteDatabase.recordAliasReassignmentAttention(address: String, newContactId: String) {
    val abandoned = rawQuery("""SELECT e.event_id,c.revision FROM studio_contacts c
        JOIN inbound_events e ON e.event_id=c.latest_event_id
        WHERE c.id<>? AND e.recipient=? AND (c.number=? OR EXISTS (
            SELECT 1 FROM contact_aliases a WHERE a.address=? AND a.region='' AND a.contact_id=c.id
        ))""", arrayOf(newContactId, address, address, address)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1)) }
    }
    abandoned.forEach { (eventId, revision) ->
        if (!staffHandledSource(eventId) && !hasStudioSendClaim(eventId) && !hasOpenStaffAttention(eventId)) {
            recordStudioStaffAttention(eventId, revision, "IDENTITY_REASSIGNED")
        }
    }
}

private fun SQLiteDatabase.hasOpenStaffAttention(eventId: String): Boolean = rawQuery(
    "SELECT 1 FROM staff_attention WHERE event_id=? AND resolved_at IS NULL LIMIT 1",
    arrayOf(eventId),
).use { it.moveToFirst() }
