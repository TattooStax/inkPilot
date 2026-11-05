package org.fossify.messages.inkpilot.studio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

private const val FIXTURE = "studio_image_orphan_recovery"

/** Separate instrumentation invocations with a force-stop between them prove restart recovery. */
@RunWith(AndroidJUnit4::class)
class StudioImageOrphanPreparationTest {
    @Test fun prepareCommittedAndInterruptedImports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-image-recovery-${UUID.randomUUID()}.db"
        val session = UUID.randomUUID().toString()
        val abandoned = UUID.randomUUID().toString()
        val committed = UUID.randomUUID().toString()
        val ambiguous = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "studio-images").apply { mkdirs() }
        File(directory, "$abandoned.image").writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "$committed.image").writeBytes(byteArrayOf(4, 5, 6))
        File(directory, "$ambiguous.image").writeBytes(byteArrayOf(7))
        File(directory, "import-$session-$abandoned.journal").writeBytes(byteArrayOf(1))
        File(directory, "import-$session-$committed.journal").writeBytes(byteArrayOf(1))
        File(directory, "import-$session-$abandoned.part").writeBytes(byteArrayOf(1))
        StudioRepository(context, name).use { repository ->
            val store = StudioAlbumStore(repository)
            store.addOriginal(store.albums().first().id, "$committed.image", "Synthetic committed image")
            assertTrue(store.hasOriginalFilename("$committed.image"))
            assertFalse(store.hasOriginalFilename("$abandoned.image"))
        }
        assertTrue(context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE).edit()
            .putString("database", name).putString("session", session)
            .putString("abandoned", abandoned).putString("committed", committed)
            .putString("ambiguous", ambiguous).commit())
    }
}

@RunWith(AndroidJUnit4::class)
class StudioImageOrphanRecoveryTest {
    @Test fun previousProcessDeletesOnlyTheUnreferencedPrivateOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE)
        val name = requireNotNull(saved.getString("database", null))
        val session = requireNotNull(saved.getString("session", null))
        val abandoned = requireNotNull(saved.getString("abandoned", null))
        val committed = requireNotNull(saved.getString("committed", null))
        val ambiguous = requireNotNull(saved.getString("ambiguous", null))
        val directory = File(context.filesDir, "studio-images")
        val abandonedFile = File(directory, "$abandoned.image")
        val committedFile = File(directory, "$committed.image")
        StudioRepository(context, name).use { repository ->
            val store = StudioAlbumStore(repository)
            StudioImageFiles(context, store).recoverIncompleteImports()
            assertFalse(abandonedFile.exists())
            assertTrue(committedFile.isFile)
            assertEquals(byteArrayOf(4, 5, 6).toList(), committedFile.readBytes().toList())
            assertTrue(store.hasOriginalFilename(committedFile.name))
        }
        assertFalse(File(directory, "import-$session-$abandoned.journal").exists())
        assertFalse(File(directory, "import-$session-$committed.journal").exists())
        assertFalse(File(directory, "import-$session-$abandoned.part").exists())
        assertTrue(File(directory, "$ambiguous.image").isFile)
        committedFile.delete()
        File(directory, "$ambiguous.image").delete()
        context.deleteDatabase(name)
        saved.edit().clear().commit()
    }
}
