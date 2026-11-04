package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** SQLiteOpenHelper owns the upgrade transaction. Copy children before rebuilding their parent. */
internal object StudioRevisionSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE draft_regenerations(event_id TEXT PRIMARY KEY NOT NULL REFERENCES inbound_events(event_id), source_revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE worker_cancellations(token TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE intake_checkpoint(id INTEGER PRIMARY KEY CHECK(id = 1), provider_id INTEGER NOT NULL)")
        db.execSQL("INSERT INTO intake_checkpoint VALUES(1, 0)")
    }

    fun upgrade(db: SQLiteDatabase) {
        val definitions = listOf("held_drafts", "send_claims", "send_parts").associateWith { table ->
            db.rawQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
                check(it.moveToFirst())
                it.getString(0)
            }
        }
        // Temporary tables deliberately have no foreign keys. Original IDs and receipts survive.
        for (table in definitions.keys) db.execSQL("CREATE TEMP TABLE copy_$table AS SELECT * FROM $table")
        for (table in definitions.keys.reversed()) db.execSQL("DROP TABLE $table")
        for ((table, sql) in definitions) {
            db.execSQL(if (table == "held_drafts") sql.replace("UNIQUE(event_id, kind)", "UNIQUE(event_id, kind, source_revision)") else sql)
            db.execSQL("INSERT INTO $table SELECT * FROM copy_$table")
            db.execSQL("DROP TABLE copy_$table")
        }
        db.execSQL("CREATE INDEX held_state_created ON held_drafts(state, created_at)")
        create(db)
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
    }
}
