package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioAlbumStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "studio-gallery-test-${UUID.randomUUID()}.db"
    private lateinit var repository: StudioRepository
    private lateinit var store: StudioAlbumStore
    private val ownFiles = mutableListOf<File>()

    @Before fun setUp() { repository = StudioRepository(context, databaseName); store = StudioAlbumStore(repository) }
    @After fun tearDown() { repository.close(); context.deleteDatabase(databaseName); ownFiles.forEach { it.delete() } }

    @Test fun importedOriginalSurvivesMembershipRemovalAndReopen() {
        val albums = store.albums()
        assertEquals(setOf("Finished Work", "Outlines", "Client References"), albums.map { it.name }.toSet())
        val source = File(File(context.cacheDir, "compressed").apply { mkdirs() }, "test-${UUID.randomUUID()}.png")
        ownFiles.add(source)
        Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).let { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", source)
        val files = StudioImageFiles(context, store)
        val imageId = files.import(uri, albums.first().id)
        val image = store.images(albums.first().id).single()
        val original = files.originalFile(image)
        ownFiles.add(original)
        assertArrayEquals(source.readBytes(), original.readBytes())
        store.addMembership(albums.last().id, imageId)
        store.addMembership(albums.last().id, imageId)
        assertEquals(1, store.images(albums.last().id).size)
        store.removeMembership(albums.first().id, imageId)
        store.removeMembership(albums.last().id, imageId)
        assertTrue(store.images(albums.first().id).isEmpty())
        assertTrue(original.isFile)
        repository.close()
        repository = StudioRepository(context, databaseName); store = StudioAlbumStore(repository)
        assertEquals(imageId, store.images(null).single().id)
        assertTrue(original.isFile)
        assertArrayEquals(source.readBytes(), original.readBytes())
    }

    @Test fun albumRenameAndArtistAssignmentPersist() {
        val id = store.saveAlbum(null, "Sleeves", "Leni")
        store.saveAlbum(id, "Client Sleeves", "Shawn")
        repository.close()
        repository = StudioRepository(context, databaseName); store = StudioAlbumStore(repository)
        assertEquals(StudioAlbum(id, "Client Sleeves", "Shawn"), store.albums().single { it.id == id })
    }

    @Test fun missingAlbumRollsBackOriginalRecord() {
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            store.addOriginal(-123, "not-created.image", "Test image")
        }
        assertTrue(store.images(null).isEmpty())
    }
}
