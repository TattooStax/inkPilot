package org.fossify.messages.inkpilot.data

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase

/** One-time bridge for edits saved by older releases outside the Studio archive. */
internal object StudioStaffEditMigration {
    fun importLegacy(db: SQLiteDatabase, preferences: SharedPreferences) {
        val entries = preferences.all
        if (entries.isEmpty()) return
        db.transaction {
            entries.forEach { (draftId, value) ->
                if (value is String) db.execSQL(
                    """UPDATE held_drafts SET staff_edit_body = ?
                       WHERE draft_id = ? AND state = 'HELD' AND staff_edit_body IS NULL""",
                    arrayOf(value, draftId),
                )
            }
        }
        // If the preferences write fails, replay is harmless: populated rows are never overwritten.
        preferences.edit().clear().commit()
    }
}
