package org.fossify.messages.inkpilot.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.databases.MessagesDatabase
import org.fossify.messages.databases.TypedMessageMigration
import org.fossify.messages.extensions.MessageProviderReadPort
import org.fossify.messages.extensions.reconcileTypedMessageCache
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.messaging.key
import org.fossify.messages.models.Message
import org.fossify.messages.models.RecycleBinMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production reconciler + generated Room DAO, with a provider port that has no write capability. */
@RunWith(AndroidJUnit4::class)
class TypedMessageReconciliationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun migrationThenDualProviderBackfillPreservesFourKeysAndUnknownWithoutProviderWrites() {
        val name = "reconciliation-upgrade.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use {
            FrozenMessagesV11.create(it)
            it.execSQL("INSERT INTO messages VALUES(42,'human scheduled',2,0,'[]',100,0,9,0,NULL,'','','',-1,1)")
            it.execSQL("INSERT INTO recycle_bin_messages VALUES(42,10)")
            it.version = 11
        }
        try {
            val db = Room.databaseBuilder(context,MessagesDatabase::class.java,name)
                .addMigrations(TypedMessageMigration).build()
            try {
                val dao = db.MessagesDao()
                val scheduledSms = dao.getAll().single()
                val scheduledMms = message(MessageOrigin.SCHEDULED_MMS)
                dao.insertMessages(scheduledMms)
                val provider = SyntheticProvider(Answer.PRESENT,Answer.PRESENT)
                val before = provider.snapshot()
                reconcileTypedMessageCache(dao,provider.messages(),provider)
                assertEquals(MessageOrigin.entries.map { message(it).key() }.toSet(),dao.getAll().map { it.key() }.toSet())
                assertEquals(scheduledSms,dao.get(scheduledSms.key()))
                assertEquals(scheduledMms,dao.get(scheduledMms.key()))
                assertEquals(listOf(RecycleBinMessage(42,10)),dao.getUnresolvedRecycleBinEntries())
                assertTrue(dao.getOldRecycleBinMessages(Long.MAX_VALUE).isEmpty())
                assertEquals(before,provider.snapshot())
                assertEquals(listOf(MessageOrigin.SMS,MessageOrigin.MMS),provider.queries)
                assertTrue(provider.cursors.all { it.isClosed })
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context,MessagesDatabase::class.java,name).build()
            try {
                assertEquals(MessageOrigin.entries.toSet(),reopened.MessagesDao().getAll().map { it.key().origin }.toSet())
                assertEquals(listOf(RecycleBinMessage(42,10)),reopened.MessagesDao().getUnresolvedRecycleBinEntries())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun uniqueNullDeniedAndAmbiguousQueriesUseProductionFailClosedPath() {
        for (sms in Answer.entries) for (mms in Answer.entries) {
            val db = Room.inMemoryDatabaseBuilder(context,MessagesDatabase::class.java).build()
            try {
                val dao = db.MessagesDao()
                val provider = SyntheticProvider(sms,mms)
                val scheduled = message(MessageOrigin.SCHEDULED_SMS)
                dao.insertMessages(scheduled)
                dao.insertRecycleBinEntry(RecycleBinMessage(42,10))
                val before = provider.snapshot()
                reconcileTypedMessageCache(dao,provider.messages(),provider)
                val expected = when {
                    sms == Answer.PRESENT && mms == Answer.ABSENT -> MessageOrigin.SMS
                    mms == Answer.PRESENT && sms == Answer.ABSENT -> MessageOrigin.MMS
                    else -> null
                }
                assertEquals(setOf(scheduled.key()) + provider.messages().map { it.key() },dao.getAll().map { it.key() }.toSet())
                assertEquals(scheduled,dao.get(scheduled.key()))
                if (expected == null) {
                    assertEquals(listOf(RecycleBinMessage(42,10)),dao.getUnresolvedRecycleBinEntries())
                    assertTrue(dao.getAllRecycleBinMessages().isEmpty())
                } else {
                    assertTrue(dao.getUnresolvedRecycleBinEntries().isEmpty())
                    assertEquals(listOf(message(expected).key()),dao.getAllRecycleBinMessages().map { it.key() })
                }
                assertEquals(before,provider.snapshot())
                assertTrue(provider.cursors.all { it.isClosed })
            } finally { db.close() }
        }
    }

    @Test fun oldUnknownNeverBackdatesNewTypedRecycleInEitherNamespaceAcrossRestart() {
        for (origin in listOf(MessageOrigin.SMS,MessageOrigin.MMS)) {
            val name = "reconciliation-merge-${origin.name}.db"
            context.deleteDatabase(name)
            try {
                val db = Room.databaseBuilder(context,MessagesDatabase::class.java,name).build()
                try {
                    val dao = db.MessagesDao()
                    val row = message(origin)
                    dao.insertMessages(row)
                    dao.insertRecycleBinEntry(RecycleBinMessage(42,10))
                    dao.insertRecycleBinEntry(RecycleBinMessage(row.key(),200))
                    val provider = SyntheticProvider(
                        if (origin == MessageOrigin.SMS) Answer.PRESENT else Answer.ABSENT,
                        if (origin == MessageOrigin.MMS) Answer.PRESENT else Answer.ABSENT)
                    val before = provider.snapshot()
                    reconcileTypedMessageCache(dao,provider.messages(),provider)
                    assertTrue(dao.getUnresolvedRecycleBinEntries().isEmpty())
                    assertTrue(dao.getOldRecycleBinMessages(100).isEmpty())
                    assertEquals(listOf(row.key()),dao.getOldRecycleBinMessages(201).map { it.key() })
                    assertEquals(before,provider.snapshot())
                } finally { db.close() }
                val reopened = Room.databaseBuilder(context,MessagesDatabase::class.java,name).build()
                try {
                    assertTrue(reopened.MessagesDao().getOldRecycleBinMessages(100).isEmpty())
                    assertEquals(listOf(message(origin).key()),reopened.MessagesDao().getAllRecycleBinMessages().map { it.key() })
                } finally { reopened.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    private enum class Answer { PRESENT, ABSENT, NULL_CURSOR, DENIED }

    private class SyntheticProvider(private val sms: Answer, private val mms: Answer) : MessageProviderReadPort {
        val queries = arrayListOf<MessageOrigin>()
        val cursors = arrayListOf<MatrixCursor>()
        private val rows = listOfNotNull(
            if (sms == Answer.PRESENT) message(MessageOrigin.SMS) else null,
            if (mms == Answer.PRESENT) message(MessageOrigin.MMS) else null)
        fun messages() = rows.toList()
        fun snapshot() = rows.map { it.copy() }
        override fun query(origin: MessageOrigin,id: Long): Cursor? {
            assertEquals(42L,id)
            queries.add(origin)
            val answer = when (origin) {
                MessageOrigin.SMS -> sms
                MessageOrigin.MMS -> mms
                else -> error("Scheduled rows must not be queried in Telephony")
            }
            if (answer == Answer.DENIED) throw SecurityException("synthetic permission denial")
            if (answer == Answer.NULL_CURSOR) return null
            return MatrixCursor(arrayOf("_id")).also {
                if (answer == Answer.PRESENT) it.addRow(arrayOf(42L))
                cursors.add(it)
            }
        }
    }

    companion object {
        private fun message(origin: MessageOrigin) = Message(
            42,"fixture",2,0,arrayListOf(),100,false,9,
            origin == MessageOrigin.MMS || origin == MessageOrigin.SCHEDULED_MMS,
            null,"","","",-1,
            origin == MessageOrigin.SCHEDULED_SMS || origin == MessageOrigin.SCHEDULED_MMS)
    }
}
