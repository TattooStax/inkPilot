package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** Frozen released v11 SQL. Intentionally independent of current Room entities. */
internal object FrozenMessagesV11 {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id INTEGER NOT NULL PRIMARY KEY, body TEXT NOT NULL, type INTEGER NOT NULL,
            status INTEGER NOT NULL, participants TEXT NOT NULL, date INTEGER NOT NULL,
            read INTEGER NOT NULL, thread_id INTEGER NOT NULL, is_mms INTEGER NOT NULL,
            attachment TEXT, sender_phone_number TEXT NOT NULL, sender_name TEXT NOT NULL,
            sender_photo_uri TEXT NOT NULL, subscription_id INTEGER NOT NULL, is_scheduled INTEGER NOT NULL)
        """.trimIndent())
        db.execSQL("CREATE INDEX index_messages_thread_id_date ON messages(thread_id,date)")
        db.execSQL("CREATE TABLE recycle_bin_messages(id INTEGER NOT NULL PRIMARY KEY, deleted_ts INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX index_recycle_bin_messages_id ON recycle_bin_messages(id)")
        db.execSQL("""CREATE TABLE conversations(thread_id INTEGER NOT NULL PRIMARY KEY,
            snippet TEXT NOT NULL,date INTEGER NOT NULL,read INTEGER NOT NULL,title TEXT NOT NULL,
            photo_uri TEXT NOT NULL,is_group_conversation INTEGER NOT NULL,phone_number TEXT NOT NULL,
            is_scheduled INTEGER NOT NULL,uses_custom_title INTEGER NOT NULL,archived INTEGER NOT NULL,
            unread_count INTEGER NOT NULL)""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_conversations_thread_id ON conversations(thread_id)")
        db.execSQL("CREATE TABLE drafts(thread_id INTEGER NOT NULL PRIMARY KEY,body TEXT NOT NULL,date INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE message_attachments(id INTEGER NOT NULL PRIMARY KEY,text TEXT NOT NULL,attachments TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE attachments(id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id INTEGER NOT NULL,uri_string TEXT NOT NULL,mimetype TEXT NOT NULL,
            width INTEGER NOT NULL,height INTEGER NOT NULL,filename TEXT NOT NULL)""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_attachments_message_id ON attachments(message_id)")
    }
}
