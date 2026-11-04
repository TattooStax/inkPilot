package org.fossify.messages.inkpilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.databases.MessagesDatabase
import org.fossify.messages.databases.TypedMessageMigration
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.messaging.key
import org.fossify.messages.models.Message
import org.fossify.messages.models.RecycleBinMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All fixtures are private test databases; never touches Telephony or the app database. */
@RunWith(AndroidJUnit4::class)
class MessageMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun frozenV11PreservesHumanRowsAndOpaqueMediaAcrossRestart() {
        val name = "typed-migration-fixture.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            FrozenMessagesV11.create(old)
            old.execSQL("INSERT INTO messages VALUES(42,'cache',1,0,'[]',100,0,9,1,?, '', '', '', -1,0)",
                arrayOf("{\"text\":\"media\",\"attachments\":[]}"))
            old.execSQL("INSERT INTO messages VALUES(77,'human scheduled',2,0,'[]',200,0,9,0,NULL,'','','',-1,1)")
            old.execSQL("INSERT INTO drafts VALUES(9,'human draft',123)")
            old.execSQL("INSERT INTO conversations VALUES(9,'archive',100,0,'name','',0,'+15550000',0,0,1,0)")
            old.execSQL("INSERT INTO recycle_bin_messages VALUES(42,123)")
            old.execSQL("INSERT INTO message_attachments VALUES(42,'media','[]')")
            old.execSQL("INSERT INTO attachments VALUES(1,42,'content://fixture/42','image/png',2,3,'image.png')")
            old.version = 11
        }
        fun open() = Room.databaseBuilder(context, MessagesDatabase::class.java, name)
            .addMigrations(TypedMessageMigration).build()
        try {
            open().use { db ->
                val dao = db.MessagesDao()
                assertEquals(setOf(42L,77L), dao.getAll().map { it.id }.toSet())
                assertEquals("human scheduled", dao.getAll().single { it.isScheduled }.body)
                assertEquals(RecycleBinMessage(42,123), dao.getUnresolvedRecycleBinEntries().single())
                assertTrue(dao.getAllRecycleBinMessages().isEmpty())
                assertTrue(dao.getOldRecycleBinMessages(Long.MAX_VALUE).isEmpty())
                db.openHelper.writableDatabase.query("SELECT body FROM drafts").use { assertTrue(it.moveToFirst()); assertEquals("human draft",it.getString(0)) }
                db.openHelper.writableDatabase.query("SELECT archived FROM conversations").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
                db.openHelper.writableDatabase.query("SELECT uri_string FROM attachments").use { assertTrue(it.moveToFirst()); assertEquals("content://fixture/42",it.getString(0)) }
                db.openHelper.writableDatabase.query("SELECT attachments FROM message_attachments").use { assertTrue(it.moveToFirst()); assertEquals("[]",it.getString(0)) }
                // Provider refresh can restore both source rows without overwriting local work.
                dao.insertMessages(message(MessageOrigin.SMS), message(MessageOrigin.MMS))
                assertEquals(3, dao.getAll().size)
                assertEquals(1, dao.getUnresolvedRecycleBinEntries().size)
            }
            open().use { db ->
                assertEquals(3, db.MessagesDao().getAll().size)
                assertEquals(1, db.MessagesDao().getUnresolvedRecycleBinEntries().size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun equalIdsSurviveTypedRecycleRestoreDeleteAndRestart() {
        val name = "typed-four-way.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, MessagesDatabase::class.java, name).build().use { db ->
                val dao = db.MessagesDao()
                val all = MessageOrigin.entries.map { message(it) }
                dao.insertMessages(*all.toTypedArray())
                assertEquals(4, dao.getAll().size)
                all.forEach { dao.insertRecycleBinEntry(RecycleBinMessage(it.key(), 123)) }
                assertEquals(4, dao.getAllRecycleBinMessages().size)
                dao.deleteFromRecycleBin(all[1].key())
                assertEquals(3, dao.getAllRecycleBinMessages().size)
                dao.delete(all[2].key())
                assertEquals(setOf(all[0].key(),all[1].key(),all[3].key()),dao.getAll().map { it.key() }.toSet())
                assertEquals(all[3],dao.get(all[3].key()))
                assertEquals(2,dao.getAllRecycleBinMessages().size)
            }
            Room.databaseBuilder(context, MessagesDatabase::class.java, name).build().use {
                assertEquals(3,it.MessagesDao().getAll().size)
                assertEquals(2,it.MessagesDao().getAllRecycleBinMessages().size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun ambiguousAbsentOrDeniedProviderQueriesNeverAssignAnOrigin() {
        for (sms in listOf(true,false,null)) for (mms in listOf(true,false,null)) {
            val resolved = org.fossify.messages.extensions.resolveLegacyRecycleOrigin(sms,mms)
            when {
                sms == true && mms == false -> assertEquals(MessageOrigin.SMS,resolved)
                mms == true && sms == false -> assertEquals(MessageOrigin.MMS,resolved)
                else -> assertNull(resolved)
            }
        }
    }

    @Test fun failedUpgradeRollsBackAndCanRetryWithoutLosingV11Rows() {
        val name = "typed-migration-rollback.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            FrozenMessagesV11.create(it)
            it.execSQL("INSERT INTO drafts VALUES(9,'keep through rollback',1)")
            it.execSQL("INSERT INTO recycle_bin_messages VALUES(42,123)")
            it.version = 11
        }
        val failing = object : androidx.room.migration.Migration(11,12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                TypedMessageMigration.migrate(db)
                throw IllegalStateException("fixture interruption")
            }
        }
        try {
            Room.databaseBuilder(context, MessagesDatabase::class.java,name)
                .addMigrations(failing).build().use {
                    try { it.openHelper.writableDatabase; fail("upgrade must fail") }
                    catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("fixture interruption")) }
                }
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(11,it.version)
                it.rawQuery("SELECT deleted_ts FROM recycle_bin_messages WHERE id=42",null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(123L,c.getLong(0))
                }
            }
            Room.databaseBuilder(context, MessagesDatabase::class.java,name)
                .addMigrations(TypedMessageMigration).build().use {
                    assertEquals(1,it.MessagesDao().getUnresolvedRecycleBinEntries().size)
                    it.openHelper.writableDatabase.query("SELECT body FROM drafts").use { c ->
                        assertTrue(c.moveToFirst()); assertEquals("keep through rollback",c.getString(0))
                    }
                }
        } finally { context.deleteDatabase(name) }
    }

    private fun message(origin: MessageOrigin) = Message(
        42,"fixture",2,0,arrayListOf(),100,false,9,
        origin == MessageOrigin.MMS || origin == MessageOrigin.SCHEDULED_MMS,
        null,"","","",-1,
        origin == MessageOrigin.SCHEDULED_SMS || origin == MessageOrigin.SCHEDULED_MMS
    )
}

private inline fun <R> MessagesDatabase.use(block: (MessagesDatabase) -> R): R =
    try { block(this) } finally { close() }
