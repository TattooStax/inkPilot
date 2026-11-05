package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

internal object StudioStaffEditSchema {
    fun upgrade(db: SQLiteDatabase) {
        val present = db.rawQuery("PRAGMA table_info(held_drafts)", null).use { columns ->
            val name = columns.getColumnIndexOrThrow("name")
            generateSequence { if (columns.moveToNext()) columns.getString(name) else null }
                .any { it == "staff_edit_body" }
        }
        if (!present) db.execSQL("ALTER TABLE held_drafts ADD COLUMN staff_edit_body TEXT")
    }
}
