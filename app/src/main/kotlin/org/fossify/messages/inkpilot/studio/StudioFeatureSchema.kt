package org.fossify.messages.inkpilot.studio

import android.database.sqlite.SQLiteDatabase

/** Part of the canonical studio database; no independent business-record store. */
internal object StudioFeatureSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE studio_albums (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE,
            artist TEXT NOT NULL DEFAULT '')""")
        db.execSQL("""CREATE TABLE studio_images (
            id INTEGER PRIMARY KEY AUTOINCREMENT, filename TEXT NOT NULL UNIQUE,
            display_name TEXT NOT NULL, imported_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE studio_album_images (
            album_id INTEGER NOT NULL REFERENCES studio_albums(id),
            image_id INTEGER NOT NULL REFERENCES studio_images(id),
            PRIMARY KEY(album_id,image_id))""")
        db.execSQL("""CREATE TABLE studio_staff (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE)""")
        db.execSQL("""CREATE TABLE studio_calendar (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL,
            staff_id INTEGER NOT NULL REFERENCES studio_staff(id), starts_at INTEGER NOT NULL,
            ends_at INTEGER NOT NULL, zone_id TEXT NOT NULL, room TEXT NOT NULL COLLATE NOCASE,
            status TEXT NOT NULL DEFAULT 'scheduled')""")
        db.execSQL("CREATE INDEX studio_calendar_time ON studio_calendar(starts_at,ends_at,status)")
        listOf("Finished Work", "Outlines", "Client References").forEach { name ->
            db.execSQL("INSERT INTO studio_albums(name) VALUES(?)", arrayOf(name))
        }
    }
}
