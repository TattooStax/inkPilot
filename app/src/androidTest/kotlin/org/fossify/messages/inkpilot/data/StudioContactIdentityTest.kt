package org.fossify.messages.inkpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioContactIdentityTest : StudioDatabaseTestBase() {
    @Test fun observerStartsAfterGrantStopsOnRevokeAndRetriesRegistrationFailure() {
        val lifecycle = org.fossify.messages.ContactsPermissionLifecycle()
        var starts = 0
        var stops = 0
        lifecycle.reconcile(false, { starts++ }, { stops++ })
        assertEquals(0, starts)
        lifecycle.reconcile(true, { starts++ }, { stops++ })
        assertTrue(lifecycle.registered)
        lifecycle.reconcile(true, { starts++ }, { stops++ })
        assertEquals(1, starts)
        lifecycle.reconcile(false, { starts++ }, { stops++ })
        assertFalse(lifecycle.registered)
        assertEquals(2, stops)
        lifecycle.reconcile(true, { throw SecurityException("Synthetic revoke race") }, { stops++ })
        assertFalse(lifecycle.registered)
        lifecycle.reconcile(true, { starts++ }, { stops++ })
        assertTrue(lifecycle.registered)
    }

    @Test fun blankNameOnExistingNumberPreservesStaffLabelAndRevision() {
        val named = repository.upsertContact("+15550101111", "Maya")
        val again = repository.upsertContact(named.number, "   ")
        assertEquals(named.id, again.id)
        assertEquals("Maya", again.displayName)
        assertEquals(named.revision, again.revision)

        repository.reviewAlias("+15550102222", "", named.id)
        val afterReview = requireNotNull(repository.contact(named.id))
        val alias = repository.upsertContact("+15550102222", "")
        assertEquals(named.id, alias.id)
        assertEquals("Maya", alias.displayName)
        assertEquals(afterReview.revision, alias.revision)
    }

    @Test fun countryCodeCandidatesRequireReviewWithoutMerging() {
        val local = repository.upsertContact("5551234567", "Local")
        val global = repository.upsertContact("+15551234567", "Global")
        assertTrue(ContactResolver(repository).resolve(local.number, "US") is ContactMatch.Ambiguous)
        assertEquals(2, repository.contacts().size)
        repository.reviewAlias(local.number, "US", global.id)
        assertEquals(ContactMatch.Unique(global.id), repository.resolveContact(local.number, "US"))
        assertEquals(2, repository.contacts().size)
        assertEquals(ContactMatch.Missing, repository.resolveContact("+4499999999", "GB"))
        val previous = repository.recordInbound("previous-owner", 98, local.number, "Synthetic", "Local", 9, 1)
        repository.reviewAlias(local.number, "", global.id)
        assertNull(repository.readSource(previous.eventId))
        repository.reviewAlias("5559998888", "", global.id)
        assertEquals(global.id, repository.upsertContact("5559998888", "Global").id)
        assertEquals(2, repository.contacts().size)
        val request = repository.recordInbound("alias-inbound", 99, "5559998888", "Synthetic", "Global", 10, 1)
        assertEquals(global.id, request.contactId)
        assertEquals("5559998888", repository.readSource(request.eventId)?.recipient)
        val received = repository.recordInbound("new-inbound", 100, "+15558889999", "Synthetic", "New", 11, 1)
        assertEquals(ContactMatch.Unique(received.contactId), repository.resolveContact("+15558889999", ""))
    }

    @Test fun memoryAndPauseRollbackTogetherAndExportRetainsSource() {
        val c = repository.upsertContact("+15550000001", "Synthetic")
        repository.saveContactEdit(c.id, "Original", false, "Consultation")
        repository.database.execSQL("CREATE TRIGGER reject_edit BEFORE UPDATE ON studio_contacts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertTrue(runCatching { repository.saveContactEdit(c.id, "Changed", true, "Correction") }.isFailure)
        assertEquals("Original", repository.memoryEntry(c.id)?.body)
        assertFalse(requireNotNull(repository.contact(c.id)).paused)
        repository.database.execSQL("DROP TRIGGER reject_edit")
        repository.saveContactEdit(c.id, "Corrected", true, "Staff correction")
        assertTrue(repository.exportMemory(c.id).contains("Staff correction"))
        assertEquals("Corrected", repository.read(c.id).single().text)
        repository.forgetMemory(c.id)
        assertNull(repository.memoryEntry(c.id))
        assertTrue(repository.read(c.id).isEmpty())
    }

    @Test fun actualV2MigrationCopiesNotesAsPendingWithoutChangingIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "identity-migration-${UUID.randomUUID()}.db"
        try {
            StudioV1Fixture(context, name).use { helper ->
                val db = helper.writableDatabase
                StudioRevisionSchema.upgrade(db)
                db.execSQL("INSERT INTO studio_contacts(id,number,display_name,memory) VALUES('a','5551234567','A','Legacy memory')")
                db.execSQL("INSERT INTO studio_contacts(id,number,display_name) VALUES('b','+15551234567','B')")
                db.version = 2
            }
            StudioRepository(context, name).use { migrated ->
                assertEquals(7, migrated.database.version)
                assertEquals(2, migrated.contacts().size)
                assertEquals(IdentityReviewState.PENDING, migrated.memoryEntry("a")?.reviewState)
                assertEquals("Legacy memory", migrated.contact("a")?.memory)
                assertTrue(migrated.read("a").isEmpty())
                assertTrue(migrated.resolveContact("5551234567", "US") is ContactMatch.Ambiguous)
                migrated.database.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
