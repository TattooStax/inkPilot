package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioImageLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "synthetic-image-links-${UUID.randomUUID()}.db"
    private var repository = StudioRepository(context, name)
    private val cleanup = mutableListOf<File>()
    @After fun clean() { repository.close(); context.deleteDatabase(name); cleanup.forEach { it.delete() } }

    @Test fun privateOriginalAndTypedLinksSurviveGrantLossRemovalAndRestart() {
        val contact = repository.upsertContact("+12025550149", "Synthetic")
        val source = File(File(context.cacheDir, "compressed").apply { mkdirs() }, "synthetic-${UUID.randomUUID()}.png")
        cleanup.add(source)
        Bitmap.createBitmap(8, 9, Bitmap.Config.ARGB_8888).let { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", source)
        var store = StudioAlbumStore(repository)
        val referenceAlbum = store.albums().single { it.name == "Client References" }
        store.saveAlbum(referenceAlbum.id, "client references", "")
        val files = StudioImageFiles(context, store)
        repository.database.execSQL("INSERT INTO studio_staff(id,name) VALUES(1901,'Synthetic artist')")
        repository.database.execSQL("""INSERT INTO studio_calendar(id,title,kind,staff_id,starts_at,ends_at,zone_id,room)
            VALUES(1902,'Synthetic appointment','appointment',1901,1,2,'UTC','')""")
        val link = StudioImageLink(contact.id, MessageKey(MessageOrigin.MMS, 41), 73,
            projectId = "synthetic-project", staffId = 1901, appointmentId = 1902)
        val id = files.importMmsOriginal(uri, link)
        val original = files.originalFile(store.linkedImages(threadId = 73).single())
        cleanup.add(original)
        val bytes = source.readBytes()
        assertArrayEquals(bytes, original.readBytes())
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(source.delete())
        // Dedup must work without reopening an expired source URI.
        assertEquals(id, files.importMmsOriginal(uri, link))
        assertEquals(1, store.images(null).size)
        store.albums().forEach { store.removeMembership(it.id, id) }
        repository.close(); repository = StudioRepository(context, name); store = StudioAlbumStore(repository)
        assertEquals(id, store.linkedImages(contactId = contact.id).single().id)
        repository.database.rawQuery("SELECT project_id,staff_id,appointment_id,source_origin,source_id FROM image_links", null).use {
            assertTrue(it.moveToFirst()); assertEquals("synthetic-project", it.getString(0))
            assertEquals(1901L, it.getLong(1)); assertEquals(1902L, it.getLong(2))
            assertEquals("MMS", it.getString(3)); assertEquals(41L, it.getLong(4))
        }
        assertTrue(store.linkedImages(threadId = 74).isEmpty())
        val reopenedFiles = StudioImageFiles(context, store)
        assertEquals(id, reopenedFiles.importReferenceOriginal(uri, StudioImageLink(contact.id, null, 101)))
        assertEquals(id, reopenedFiles.importReferenceOriginal(uri, StudioImageLink(contact.id, null, 102)))
        assertEquals(id, reopenedFiles.importReferenceOriginal(uri, StudioImageLink(contact.id, null, null)))
        assertEquals(id, store.linkedImages(threadId = 101).single().id)
        assertEquals(id, store.linkedImages(threadId = 102).single().id)
        assertEquals(id, store.linkedImages(contactId = contact.id).single().id)
        assertTrue(store.linkedImages(threadId = 73, offset = 100).isEmpty())
        assertArrayEquals(bytes, original.readBytes())
        repository.database.rawQuery("SELECT ownership,byte_count,width,height FROM image_original_sources", null).use {
            assertTrue(it.moveToFirst()); assertEquals("PRIVATE_COPY", it.getString(0))
            assertEquals(bytes.size.toLong(), it.getLong(1)); assertEquals(8, it.getInt(2)); assertEquals(9, it.getInt(3))
        }
    }

    @Test fun v3MigrationPreservesRecordsAndMemberships() {
        val contact = repository.upsertContact("+12025550148", "Synthetic migration")
        val store = StudioAlbumStore(repository)
        val album = store.albums().first().id
        val id = store.addOriginal(album, "synthetic-old.image", "Old")
        repository.database.execSQL("DROP TABLE image_links")
        repository.database.execSQL("DROP TABLE image_original_sources")
        repository.database.version = 3
        repository.close(); repository = StudioRepository(context, name)
        assertEquals(4, repository.database.version)
        assertEquals(contact.id, repository.contact(contact.id)?.id)
        assertEquals(id, StudioAlbumStore(repository).images(album).single().id)
        assertTrue(StudioAlbumStore(repository).linkedImages(contactId = contact.id).isEmpty())
    }

    @Test fun linkedGalleryPagesWithoutDuplicates() {
        val contact = repository.upsertContact("+12025550146", "Synthetic paging")
        val store = StudioAlbumStore(repository)
        val album = store.albums().first().id
        repeat(105) { index ->
            val id = store.addOriginal(album, "synthetic-page-$index.image", "Image $index")
            store.linkOriginal(id, StudioImageLink(contact.id, MessageKey(MessageOrigin.MMS, index.toLong()), 99))
        }
        val first = store.linkedImages(threadId = 99)
        val next = store.linkedImages(contactId = contact.id, offset = 100)
        assertEquals(100, first.size); assertEquals(5, next.size)
        assertEquals(105, (first + next).map { it.id }.toSet().size)
    }

    @Test fun invalidLinkRollsBackAndDoesNotConflateSmsWithMms() {
        val contact = repository.upsertContact("+12025550147", "Synthetic validation")
        val store = StudioAlbumStore(repository)
        val id = store.addOriginal(store.albums().first().id, "synthetic-only.image", "Synthetic")
        assertThrows(IllegalArgumentException::class.java) {
            store.linkOriginal(id, StudioImageLink(contact.id, MessageKey(MessageOrigin.SMS, 41), 73))
        }
        assertTrue(store.linkedImages(contactId = contact.id).isEmpty())
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            store.saveMmsOriginal("content://synthetic/1", "new.image", "New", "image/png", 2, 1, 1,
                StudioImageLink("missing-contact", MessageKey(MessageOrigin.MMS, 41), 73))
        }
        assertNull(store.originalForSource("content://synthetic/1"))
        assertEquals(1, store.images(null).size)
    }
}
