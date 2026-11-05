package org.fossify.messages.inkpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioStaffQuestionMigrationTest {
    @Test fun v10SparseHistoryAndSendReceiptRemainUntouchedAfterUpgradeAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "staff-question-v10-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("""CREATE TABLE inbound_events(event_id TEXT PRIMARY KEY,
                    thread_id TEXT, received_at INTEGER)""")
                db.execSQL("""CREATE TABLE held_drafts(draft_id TEXT PRIMARY KEY, thread_id TEXT,
                    state TEXT, created_at INTEGER, body TEXT, renderer_policy TEXT)""")
                db.execSQL("""INSERT INTO held_drafts VALUES('old','42','SENDING',1000,
                    'Earlier model reply','LEGACY_RAW')""")
                db.execSQL("CREATE TABLE send_claims(draft_id TEXT PRIMARY KEY,body TEXT)")
                db.execSQL("INSERT INTO send_claims VALUES('old','Earlier model reply')")
                db.version = 10
            }
            repeat(2) {
                StudioDatabase(context, name).use { helper ->
                    val db = helper.writableDatabase
                    assertEquals(11, db.version)
                    db.rawQuery("""SELECT h.body, h.renderer_policy, h.staff_question_policy,
                        s.body FROM held_drafts h JOIN send_claims s ON s.draft_id=h.draft_id""", null).use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("Earlier model reply", row.getString(0))
                        assertEquals("LEGACY_RAW", row.getString(1))
                        assertTrue(row.isNull(2))
                        assertEquals("Earlier model reply", row.getString(3))
                        assertFalse(row.moveToNext())
                    }
                    assertTrue(runCatching {
                        db.execSQL("UPDATE held_drafts SET staff_question_policy='UNQUALIFIED' WHERE draft_id='old'")
                    }.isFailure)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
