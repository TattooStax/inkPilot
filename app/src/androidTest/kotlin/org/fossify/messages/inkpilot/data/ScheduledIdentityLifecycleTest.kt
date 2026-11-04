package org.fossify.messages.inkpilot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.databases.MessagesDatabase
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.updateScheduledMessagesThreadId
import org.fossify.messages.messaging.*
import org.fossify.messages.models.Message
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local rows only. No alarm registration, Telephony writes, or carrier handoff. */
@RunWith(AndroidJUnit4::class)
class ScheduledIdentityLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun message(id: Long = System.nanoTime(), mms: Boolean = false) = Message(id,
        "original scheduled text", 6, 0, arrayListOf(), 1, false, id, mms, null, "", "", "", -1, true)

    @Test fun preMigrationTypedAlarmResolvesCurrentThreadAfterRoomMove() {
        val row = message()
        val oldIntent = context.scheduledAlarmIntent(row.key(), row.threadId)
        try {
            context.queueScheduled(row) { }
            context.updateScheduledMessagesThreadId(listOf(row), row.threadId + 1)
            val resolved = context.resolveScheduledAlarm(oldIntent)
            assertEquals(row.key(), resolved?.key())
            assertEquals(row.threadId + 1, resolved?.threadId)
            ScheduledVerdicts(context).use { journal ->
                val claimed = journal.claimPrepared(row.key()) { context.messagesDB.get(row.key())?.let { it to 1 } }
                assertEquals(row.threadId + 1, claimed?.threadId)
                assertFalse(journal.claim(row.key(), 1))
            }
        } finally { context.messagesDB.delete(row.key()) }
    }

    @Test fun crossKindReplacementRollbackAndReopenKeepExactlyOneIdentity() {
        for (oldMms in listOf(false, true)) {
            val name = "scheduled-replace-${System.nanoTime()}.db"
            fun open() = Room.databaseBuilder(context, MessagesDatabase::class.java, name).build()
            val original = message(42, oldMms)
            val edited = original.copy(isMMS = !oldMms, body = "edited scheduled text")
            try {
                open().use { db ->
                    db.MessagesDao().insertOrUpdate(original)
                    // Interrupt after the new identity is inserted, at the old identity's deletion.
                    db.openHelper.writableDatabase.execSQL("CREATE TRIGGER stop_replacement BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT, 'controlled interruption'); END")
                    assertTrue(runCatching { db.MessagesDao().replaceScheduled(edited, original.key()) }.isFailure)
                }
                open().use { db ->
                    assertEquals(listOf(original), db.MessagesDao().getAll())
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER stop_replacement")
                    db.MessagesDao().replaceScheduled(edited, original.key())
                }
                open().use { db ->
                    assertEquals(listOf(edited), db.MessagesDao().getAll())
                    assertNull(db.MessagesDao().get(original.key()))
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}

private inline fun <T> MessagesDatabase.use(block: (MessagesDatabase) -> T): T =
    try { block(this) } finally { close() }
