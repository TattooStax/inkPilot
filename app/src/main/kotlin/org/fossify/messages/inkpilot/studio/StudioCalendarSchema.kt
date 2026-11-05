package org.fossify.messages.inkpilot.studio

import android.database.sqlite.SQLiteDatabase
import java.time.ZoneId

/** Additive v4 -> v5 calendar upgrade. Existing rows, IDs and image links are retained. */
internal object StudioCalendarSchema {
    fun create(db: SQLiteDatabase) {
        upgrade(db)
    }

    fun upgrade(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE studio_calendar ADD COLUMN contact_id TEXT REFERENCES studio_contacts(id)")
        db.execSQL("ALTER TABLE studio_calendar ADD COLUMN project_id TEXT")
        db.execSQL("ALTER TABLE studio_calendar ADD COLUMN reminder_at INTEGER")
        db.execSQL("ALTER TABLE studio_calendar ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE studio_staff_hours (
            staff_id INTEGER NOT NULL REFERENCES studio_staff(id), weekday INTEGER NOT NULL CHECK(weekday BETWEEN 1 AND 7),
            opens_minute INTEGER NOT NULL CHECK(opens_minute BETWEEN 0 AND 1439),
            closes_minute INTEGER NOT NULL CHECK(closes_minute BETWEEN 1 AND 1440),
            CHECK(closes_minute > opens_minute), PRIMARY KEY(staff_id,weekday))""")
        db.execSQL("""CREATE TABLE studio_time_off (
            id INTEGER PRIMARY KEY AUTOINCREMENT, staff_id INTEGER NOT NULL REFERENCES studio_staff(id),
            starts_at INTEGER NOT NULL, ends_at INTEGER NOT NULL CHECK(ends_at > starts_at),
            label TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX studio_time_off_window ON studio_time_off(staff_id,starts_at,ends_at)")
        db.execSQL("CREATE INDEX studio_calendar_reminder ON studio_calendar(reminder_at,status)")
        db.execSQL("CREATE TABLE studio_calendar_settings (id INTEGER PRIMARY KEY CHECK(id=1),zone_id TEXT NOT NULL)")
        db.execSQL("INSERT INTO studio_calendar_settings(id,zone_id) VALUES(1,?)", arrayOf(ZoneId.systemDefault().id))
    }
}
