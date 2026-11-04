package org.fossify.messages.databases

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room wraps schema upgrades in a transaction, including its schema validation. */
object TypedMessageMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE messages_typed (
                id INTEGER NOT NULL, body TEXT NOT NULL, type INTEGER NOT NULL,
                status INTEGER NOT NULL, participants TEXT NOT NULL, date INTEGER NOT NULL,
                read INTEGER NOT NULL, thread_id INTEGER NOT NULL, is_mms INTEGER NOT NULL,
                attachment TEXT, sender_phone_number TEXT NOT NULL, sender_name TEXT NOT NULL,
                sender_photo_uri TEXT NOT NULL, subscription_id INTEGER NOT NULL,
                is_scheduled INTEGER NOT NULL, PRIMARY KEY(id, is_mms, is_scheduled))
        """.trimIndent())
        val columns = "id, body, type, status, participants, date, read, thread_id, is_mms, " +
            "attachment, sender_phone_number, sender_name, sender_photo_uri, subscription_id, is_scheduled"
        db.execSQL("INSERT INTO messages_typed ($columns) SELECT $columns FROM messages")
        db.execSQL("DROP TABLE messages")
        db.execSQL("ALTER TABLE messages_typed RENAME TO messages")
        db.execSQL("CREATE INDEX index_messages_thread_id_date ON messages(thread_id, date)")
        db.execSQL("""
            CREATE TABLE recycle_bin_messages_typed (
                id INTEGER NOT NULL, deleted_ts INTEGER NOT NULL, kind_code INTEGER NOT NULL,
                PRIMARY KEY(id, kind_code))
        """.trimIndent())
        // A cached row cannot establish which provider namespace a historical raw ID meant.
        db.execSQL("INSERT INTO recycle_bin_messages_typed SELECT id, deleted_ts, 4 FROM recycle_bin_messages")
        db.execSQL("DROP TABLE recycle_bin_messages")
        db.execSQL("ALTER TABLE recycle_bin_messages_typed RENAME TO recycle_bin_messages")
    }
}
