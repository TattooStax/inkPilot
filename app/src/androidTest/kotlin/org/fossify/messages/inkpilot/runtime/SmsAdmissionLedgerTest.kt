package org.fossify.messages.inkpilot.runtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SmsAdmissionLedgerTest {
    @Test fun v1FilteredReviewSurvivesPositiveAdmissionUpgrade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sms-admission-${UUID.randomUUID()}.db"
        try {
            object : SQLiteOpenHelper(context, name, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE skipped_sms (receipt TEXT PRIMARY KEY NOT NULL, sender TEXT NOT NULL, " +
                        "body TEXT NOT NULL, received_at INTEGER NOT NULL, subscription_id INTEGER NOT NULL, reason TEXT NOT NULL)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture only")
            }.use { helper ->
                helper.writableDatabase.execSQL("INSERT INTO skipped_sms VALUES('legacy', '+15550100101', " +
                    "'Synthetic filtered request', 1234, 1, 'blocked')")
                assertEquals(1, helper.writableDatabase.version)
            }
            FilteredSmsJournal(context, name).use { journal ->
                assertEquals(1, journal.count())
                assertEquals("blocked", journal.latest().single().reason)
                journal.admissions.admitNative(7, 42, "+15550100102", "Synthetic accepted request", 1235, 1)
                assertTrue(journal.admissions.admitsNative(7, 42, "+15550100102", "Synthetic accepted request", 1235, 1))
                assertFalse(journal.admissions.admitsNative(7, 42, "+15550100102", "Altered request", 1235, 1))
                journal.admissions.pruneAdmissions(7)
                assertFalse(journal.admissions.admitsNative(7, 42, "+15550100102", "Synthetic accepted request", 1235, 1))
                assertEquals(1, journal.count())
            }
        } finally { context.deleteDatabase(name) }
    }
}
