package org.fossify.messages.inkpilot.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.studio.StudioAlbumStore
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class StudioArchiveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "studio-archive-" + UUID.randomUUID() + ".db"
    private val originalName = "synthetic-" + UUID.randomUUID() + ".image"
    private val original get() = File(context.filesDir, "studio-images/" + originalName)

    @Before fun emulatorOnly() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only." }
    }

    @After fun removeOwnedFixture() {
        original.delete()
        context.deleteDatabase(databaseName)
    }

    @Test fun snapshotIncludesRetainedUnlinkedOriginalAndVerifiableRows() {
        val repository = StudioRepository(context, databaseName)
        try {
            val contact = repository.upsertContact("+15550001111", "Synthetic Archive Client")
            repository.saveMemory(contact.id, "Synthetic archive memory")
            val request = repository.recordInbound("archive-intake", 88, contact.number,
                "I want a small rose", "Synthetic Archive Client", 1000, 3)
            assertTrue(repository.reviewLeadIntake(request, ReplyLanguage.EN,
                setOf(LeadQuestion.PLACEMENT)) is LeadIntakeReviewResult.Saved)
            val albums = StudioAlbumStore(repository)
            val albumId = albums.saveAlbum(null, "Synthetic Album", "")
            original.parentFile!!.mkdirs()
            val bytes = "Synthetic original image bytes".toByteArray()
            original.writeBytes(bytes)
            val imageId = albums.addOriginal(albumId, originalName, "Synthetic Original")
            albums.removeMembership(albumId, imageId)

            val output = ByteArrayOutputStream()
            val summary = StudioArchiveExporter(context, repository).export(output)
            assertTrue(summary.tables >= 1)
            assertEquals(1, summary.originals)
            val entries = readZip(output.toByteArray())
            val manifest = JSONObject(requireNotNull(entries["manifest.json"]).toString(Charsets.UTF_8))
            assertEquals("inkpilot-studio-export-v1", manifest.getString("format"))
            assertTrue(manifest.getBoolean("plaintext"))
            assertEquals(10, manifest.getInt("studioSchemaVersion"))
            assertEquals(StudioArchiveScope.DESCRIPTION, manifest.getString("scope"))
            val inbound = requireNotNull(entries["studio/tables/inbound_events.jsonl"])
                .toString(Charsets.UTF_8)
            assertTrue(inbound.contains("I want a small rose"))
            assertArrayEquals(bytes, entries["studio/images/" + originalName])
            val contacts = requireNotNull(entries["studio/tables/studio_contacts.jsonl"]).toString(Charsets.UTF_8)
            assertTrue(contacts.contains("Synthetic Archive Client"))
            assertTrue(contacts.contains("Synthetic archive memory"))
            val scopeRows = requireNotNull(entries["studio/tables/lead_intake_reviews.jsonl"]).toString(Charsets.UTF_8)
            assertTrue(scopeRows.contains("archive-intake"))
            assertTrue(scopeRows.contains("STAFF_UI"))
            val manifestEntries = manifest.getJSONArray("entries")
            for (i in 0 until manifestEntries.length()) {
                val item = manifestEntries.getJSONObject(i)
                val data = requireNotNull(entries[item.getString("name")])
                assertEquals(data.size.toLong(), item.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(data).hex(), item.getString("sha256"))
            }
        } finally {
            repository.close()
        }
    }

    @Test fun missingOriginalAbortsBeforeCompleteManifest() {
        val repository = StudioRepository(context, databaseName)
        try {
            val albums = StudioAlbumStore(repository)
            val albumId = albums.saveAlbum(null, "Synthetic Album", "")
            albums.addOriginal(albumId, originalName, "Missing Original")
            val output = ByteArrayOutputStream()
            assertNotNull(runCatching { StudioArchiveExporter(context, repository).export(output) }.exceptionOrNull())
            assertFalse(readZip(output.toByteArray()).containsKey("manifest.json"))
        } finally {
            repository.close()
        }
    }

    @Test fun lockingDuringStreamingStopsBeforeManifest() {
        val repository = StudioRepository(context, databaseName)
        try {
            repository.upsertContact("+15550002222", "Synthetic Lock Case")
            val output = ByteArrayOutputStream()
            var checks = 0
            val error = runCatching {
                StudioArchiveExporter(context, repository).export(output) { ++checks <= 3 }
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("locked"))
            assertFalse(readZip(output.toByteArray()).containsKey("manifest.json"))
        } finally {
            repository.close()
        }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return files
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
