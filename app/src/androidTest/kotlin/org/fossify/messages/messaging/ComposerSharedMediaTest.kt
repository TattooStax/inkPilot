package org.fossify.messages.messaging

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComposerSharedMediaTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun interruptedCopyBeforeComposerCommitIsReapedOnNextProcess() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3))
        val directory = File(context.filesDir, "composer-shares")
        try {
            val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val captured = requireNotNull(context.captureSharedComposerImages(listOf(sourceUri))).single()
            val copy = File(directory, captured.lastPathSegment!!)
            assertTrue(copy.isFile)
            context.recoverSharedComposerImages(emptySet(), activeSession = "simulated-restart")
            assertFalse(copy.exists())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith("-${copy.nameWithoutExtension}.journal") })
        } finally { source.delete() }
    }

    @Test fun committedCopySurvivesPriorProcessJournalRecovery() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3))
        val threadId = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        val directory = File(context.filesDir, "composer-shares")
        val store = ComposerStore(context)
        try {
            val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val captured = requireNotNull(context.captureSharedComposerImages(listOf(sourceUri))).single()
            val copy = File(directory, captured.lastPathSegment!!)
            val request = OutgoingMessage(threadId, "+12025550145", "Reference", listOf(captured), -1)
            assertTrue(store.save(request))
            // Model death after the composer commit but before the journal is deleted.
            val interrupted = File(directory, "share-previous-process-${copy.nameWithoutExtension}.journal")
            interrupted.writeText(copy.name)
            context.recoverSharedComposerImages(setOf(copy.name), activeSession = "simulated-restart")
            assertTrue(copy.isFile)
            assertFalse(interrupted.exists())
            assertEquals(listOf(captured), ComposerStore(context).load(threadId)?.attachmentUris)
        } finally { store.clear(threadId); source.delete() }
    }

    @Test fun canceledShareRemovesCopyAndItsRecoveryMarker() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3))
        val directory = File(context.filesDir, "composer-shares")
        try {
            val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val local = requireNotNull(context.captureSharedComposerImages(listOf(sourceUri))).single()
            val copy = File(directory, local.lastPathSegment!!)
            val journals = directory.listFiles().orEmpty().filter { it.name.endsWith("-${copy.nameWithoutExtension}.journal") }
            assertTrue(copy.isFile)
            assertEquals(1, journals.size)
            context.discardSharedComposerImages(listOf(local))
            assertFalse(copy.exists())
            assertFalse(journals.single().exists())
            assertTrue(source.isFile)
        } finally { source.delete() }
    }

    @Test fun nextSharePrunesCrashedPartialButKeepsFinishedCopy() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3))
        val directory = File(context.filesDir, "composer-shares").apply { mkdirs() }
        val orphan = File(directory, "share-previous-process.part")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
        var first: Uri? = null
        var second: Uri? = null
        try {
            val retained = requireNotNull(context.captureSharedComposerImages(listOf(uri))).single()
            first = retained
            orphan.writeBytes(byteArrayOf(7))
            val next = requireNotNull(context.captureSharedComposerImages(listOf(uri))).single()
            second = next
            assertFalse(orphan.exists())
            assertTrue(File(directory, retained.lastPathSegment!!).isFile)
            assertTrue(File(directory, next.lastPathSegment!!).isFile)
        } finally {
            context.discardSharedComposerImages(listOfNotNull(first, second))
            orphan.delete()
            source.delete()
        }
    }

    @Test fun oversizedShareLeavesNoPrivateCopy() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        val directory = File(context.filesDir, "composer-shares")
        val before = directory.listFiles().orEmpty().map(File::getName).toSet()
        try {
            source.outputStream().use { output -> output.write(ByteArray(1_048_577)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            assertNull(context.captureSharedComposerImages(listOf(uri)))
            assertEquals(before, directory.listFiles().orEmpty().map(File::getName).toSet())
        } finally { source.delete() }
    }

    @Test fun removalAndClearDeleteOnlyOwnedComposerCopy() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val threadId = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        val store = ComposerStore(context)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val first = requireNotNull(context.captureSharedComposerImages(listOf(uri))).single()
            val firstFile = File(context.filesDir, "composer-shares/${first.lastPathSegment}")
            assertTrue(firstFile.isFile)
            assertEquals("Shared image", context.composerAttachmentName(first))
            val request = OutgoingMessage(threadId, "+12025550145", "Reference", listOf(first), 1)
            assertTrue(store.save(request))
            assertTrue(store.save(request.copy(attachmentUris = emptyList())))
            assertFalse(firstFile.exists())

            val second = requireNotNull(context.captureSharedComposerImages(listOf(uri))).single()
            val secondFile = File(context.filesDir, "composer-shares/${second.lastPathSegment}")
            assertTrue(store.save(request.copy(attachmentUris = listOf(second))))
            store.clear(threadId)
            assertFalse(secondFile.exists())
            assertTrue(source.isFile)
            context.discardSharedComposerImages(listOf(Uri.parse("content://other.provider/composer_shared_images/${source.name}")))
            assertTrue(source.isFile)
        } finally {
            store.clear(threadId)
            source.delete()
        }
    }

    @Test fun confirmedMmsReleasesCopyOnlyAfterExactCallback() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.jpg")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val threadId = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        val store = ComposerStore(context)
        try {
            val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val local = requireNotNull(context.captureSharedComposerImages(listOf(sourceUri))).single()
            val copied = File(context.filesDir, "composer-shares/${local.lastPathSegment}")
            val request = OutgoingMessage(threadId, "+12025550145", "Reference", listOf(local), 1)
            assertTrue(store.save(request))
            assertTrue(store.locked { store.claim(request) })
            assertFalse(store.confirmMmsSent(threadId, "other-attempt"))
            assertTrue(copied.isFile)
            assertTrue(store.confirmMmsSent(threadId, request.attemptId))
            assertFalse(copied.exists())
            assertTrue(source.isFile)
        } finally {
            store.clear(threadId)
            source.delete()
        }
    }

    @Test fun interruptedConfirmedMmsJournalRecoversAndReleasesCopy() {
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(1, 2, 3))
        val id = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        val store = ComposerStore(context)
        try {
            val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
            val local = requireNotNull(context.captureSharedComposerImages(listOf(sourceUri))).single()
            val copy = File(context.filesDir, "composer-shares/${local.lastPathSegment}")
            val draft = OutgoingMessage(id, "+12025550145", "Reference", listOf(local), 1)
            assertTrue(store.save(draft))
            assertTrue(store.locked { store.claim(draft) })
            assertTrue(store.prefs.edit().putString("mms-confirmed:$id", draft.attemptId).commit())
            assertTrue(copy.isFile)
            assertNull(ComposerStore(context).load(id))
            assertFalse(copy.exists())
        } finally {
            store.clear(id)
            source.delete()
        }
    }
}
