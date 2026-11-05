package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class StudioStaffEditTest : StudioDatabaseTestBase() {
    @Test fun editedHeldReplySurvivesRestartAndIsIncludedInStudioExport() {
        val draft = held()
        val edit = "  Exact staff words, with spaces.  "
        assertTrue(repository.saveStaffEdit(draft.draftId, edit))
        reopen()
        assertEquals(draft.body, repository.draft(draft.draftId)?.body)
        assertEquals(edit, repository.draft(draft.draftId)?.staffEditBody)
        val output = ByteArrayOutputStream()
        StudioArchiveExporter(context, repository).export(output)
        val heldRows = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "studio/tables/held_drafts.jsonl" }
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(heldRows.contains(edit))
        assertTrue(heldRows.contains(draft.body))
    }

    @Test fun claimKeepsExactApprovedBodyAndClearsTemporaryEdit() {
        val draft = held()
        val exact = "  Please send a reference image.  "
        assertTrue(repository.saveStaffEdit(draft.draftId, exact))
        assertNull(repository.claimDraftForSend(draft.draftId, draft.body, "An older panel's reply", 3))
        assertNull(repository.getSendClaim(draft.draftId))
        val claim = requireNotNull(repository.claimDraftForSend(draft.draftId, draft.body, exact, 3))
        assertEquals(exact, claim.body)
        assertEquals(exact, repository.draft(draft.draftId)?.sendBody)
        assertNull(repository.draft(draft.draftId)?.staffEditBody)
        assertFalse(repository.saveStaffEdit(draft.draftId, "late write"))
        reopen()
        assertEquals(exact, repository.getSendClaim(draft.draftId)?.body)
        assertNull(repository.draft(draft.draftId)?.staffEditBody)
    }

    @Test fun dismissalClearsEditAndOldPreferencesCannotResurrectIt() {
        val draft = held()
        assertTrue(repository.saveStaffEdit(draft.draftId, "Staff will call"))
        assertTrue(repository.dismissDraft(draft.draftId))
        assertNull(repository.draft(draft.draftId))
        repository.database.rawQuery("SELECT staff_edit_body FROM held_drafts WHERE draft_id = ?",
            arrayOf(draft.draftId)).use { row ->
            assertTrue(row.moveToFirst())
            assertTrue(row.isNull(0))
        }
        val name = "old-edits-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences(name, 0)
        try {
            assertTrue(prefs.edit().putString(draft.draftId, "stale staff words").commit())
            StudioStaffEditMigration.importLegacy(repository.database, prefs)
            assertTrue(prefs.all.isEmpty())
            repository.database.rawQuery("SELECT staff_edit_body FROM held_drafts WHERE draft_id = ?",
                arrayOf(draft.draftId)).use { row -> assertTrue(row.moveToFirst()); assertTrue(row.isNull(0)) }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun reviewedScopeRevisionCannotApproveAnOlderStaffEdit() {
        val source = inbound()
        val draft = held(source)
        val edit = "Please send a reference image"
        assertTrue(repository.saveStaffEdit(draft.draftId, edit))
        assertTrue(repository.reviewLeadIntake(source, ReplyLanguage.EN,
            setOf(LeadQuestion.SIZE)) is LeadIntakeReviewResult.Saved)
        assertFalse(requireNotNull(repository.draft(draft.draftId)).canReview)
        assertNull(repository.claimDraftForSend(draft.draftId, draft.body, edit, 3))
        assertNull(repository.getSendClaim(draft.draftId))
    }

    @Test fun legacyImportIsIdempotentAndNeverOverwritesNewerEdits() {
        val draft = held()
        val name = "old-edits-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences(name, 0)
        try {
            assertTrue(prefs.edit().putString(draft.draftId, "Original legacy edit").commit())
            StudioDatabase(context, databaseName, prefs).use { it.writableDatabase }
            assertEquals("Original legacy edit", repository.draft(draft.draftId)?.staffEditBody)
            assertTrue(repository.saveStaffEdit(draft.draftId, "Newer saved edit"))
            assertTrue(prefs.edit().putString(draft.draftId, "Old replay").commit())
            StudioStaffEditMigration.importLegacy(repository.database, prefs)
            assertEquals("Newer saved edit", repository.draft(draft.draftId)?.staffEditBody)
            assertTrue(prefs.all.isEmpty())
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun v8HeldTableAddsNullableEditWithoutLosingHeldText() {
        val name = "v8-staff-edit-" + UUID.randomUUID() + ".db"
        val prefsName = "v8-edits-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences(prefsName, 0)
        try {
            assertTrue(prefs.edit().putString("d", "Original legacy edit")
                .putString("terminal", "Must not restore").commit())
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
                db.execSQL("CREATE TABLE inbound_events(event_id TEXT PRIMARY KEY, thread_id TEXT, received_at INTEGER)")
                db.execSQL("CREATE TABLE held_drafts(draft_id TEXT PRIMARY KEY, thread_id TEXT, state TEXT, created_at INTEGER, body TEXT)")
                db.execSQL("INSERT INTO held_drafts VALUES('d','42','HELD',1000,'Original model text')")
                db.execSQL("INSERT INTO held_drafts VALUES('terminal','42','DISMISSED',1001,'Retired model text')")
                db.version = 8
            }
            StudioDatabase(context, name, prefs).use { helper ->
                val upgraded = helper.writableDatabase
                assertEquals(10, upgraded.version)
                upgraded.rawQuery("SELECT body, staff_edit_body FROM held_drafts WHERE draft_id='d'", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("Original model text", row.getString(0))
                    assertEquals("Original legacy edit", row.getString(1))
                }
                upgraded.rawQuery("SELECT staff_edit_body FROM held_drafts WHERE draft_id='terminal'", null).use { row ->
                    assertTrue(row.moveToFirst()); assertTrue(row.isNull(0))
                }
                upgraded.execSQL("UPDATE held_drafts SET staff_edit_body='Newer edit' WHERE draft_id='d'")
            }
            assertTrue(prefs.all.isEmpty())
            assertTrue(prefs.edit().putString("d", "Old replay").commit())
            StudioDatabase(context, name, prefs).use { helper ->
                helper.writableDatabase.rawQuery("SELECT staff_edit_body FROM held_drafts WHERE draft_id='d'", null).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals("Newer edit", row.getString(0))
                }
            }
            assertTrue(prefs.all.isEmpty())
        } finally {
            context.deleteDatabase(name)
            context.deleteSharedPreferences(prefsName)
        }
    }
}
