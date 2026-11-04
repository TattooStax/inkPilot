package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Failed input at one revision must not starve later contacts or retry forever. */
internal fun SQLiteDatabase.recordStudioDraftAttempt(eventId: String, sourceRevision: Long, reason: String): Boolean {
    require(sourceRevision >= 0 && reason.isNotBlank() && reason.length <= 256)
    return transaction {
        val source = studioSource(eventId) ?: return@transaction false
        if (source.revision != sourceRevision) return@transaction false
        rawQuery("SELECT 1 FROM held_drafts WHERE event_id = ? AND source_revision = ? LIMIT 1", arrayOf(eventId, sourceRevision.toString())).use {
            if (it.moveToFirst()) return@transaction false
        }
        insertWithOnConflict("draft_attempts", null, ContentValues().apply {
            put("event_id", eventId); put("source_revision", sourceRevision); put("reason", reason)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        true
    }
}
