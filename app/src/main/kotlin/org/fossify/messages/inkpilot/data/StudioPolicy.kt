package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

internal fun SQLiteDatabase.studioDraftingEnabled(): Boolean =
    rawQuery("SELECT drafting_enabled FROM agent_settings WHERE id = 1", null).use {
        check(it.moveToFirst()) { "Agent policy is missing" }
        it.getInt(0) != 0
    }

internal fun SQLiteDatabase.studioSoul(): String =
    rawQuery("SELECT soul FROM agent_settings WHERE id = 1", null).use {
        check(it.moveToFirst()) { "Agent policy is missing" }
        it.getString(0)
    }

internal fun SQLiteDatabase.setStudioDraftingEnabled(enabled: Boolean) {
    transaction {
        if (studioDraftingEnabled() != enabled) {
            execSQL("UPDATE agent_settings SET drafting_enabled = ? WHERE id = 1", arrayOf(if (enabled) 1 else 0))
            execSQL("UPDATE studio_contacts SET revision = revision + 1")
        }
    }
}

/** Close a previously enabled RAW route and leave its current conversations for staff. */
fun StudioRepository.quarantineUnqualifiedDrafting(): Boolean {
    val db = database
    if (!db.studioDraftingEnabled()) return false
    return db.transaction {
        if (!db.studioDraftingEnabled()) return@transaction false
        db.execSQL("UPDATE agent_settings SET drafting_enabled = 0 WHERE id = 1")
        db.execSQL("UPDATE studio_contacts SET revision = revision + 1")
        db.execSQL("""
            INSERT OR IGNORE INTO staff_attention(event_id, source_revision, reason)
            SELECT e.event_id, c.revision, 'MODEL_UNQUALIFIED'
            FROM studio_contacts c JOIN inbound_events e ON e.event_id = c.latest_event_id
            WHERE c.paused = 0 AND NOT EXISTS (
                SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id = s.draft_id
                WHERE h.event_id = e.event_id
            ) AND NOT EXISTS (
                SELECT 1 FROM staff_attention a WHERE a.event_id = e.event_id AND a.resolution = 'STAFF_HANDLED'
            )
        """.trimIndent())
        true
    }
}

internal fun SQLiteDatabase.setStudioSoul(text: String) {
    require(text.length <= 8_000) { "Studio guidance is limited to 8,000 characters" }
    transaction {
        if (studioSoul() != text) {
            execSQL("UPDATE agent_settings SET soul = ? WHERE id = 1", arrayOf(text))
            execSQL("UPDATE studio_contacts SET revision = revision + 1")
        }
    }
}
