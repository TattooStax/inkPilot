package org.fossify.messages.messaging

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComposerDiscardRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun interruptedAcceptedBeforeCompletedMarkerCleansCopyWithoutReplaying() =
        interruptedAcceptedCleanup(completedBeforeRestart = false)

    @Test fun interruptedAcceptedAfterCompletedMarkerCleansCopyWithoutReplaying() =
        interruptedAcceptedCleanup(completedBeforeRestart = true)

    private fun interruptedAcceptedCleanup(completedBeforeRestart: Boolean) {
        val source = source()
        val id = id()
        val store = ComposerStore(context)
        try {
            val local = requireNotNull(context.captureSharedComposerImages(listOf(uri(source)))).single()
            val copy = copy(local.lastPathSegment!!)
            val request = OutgoingMessage(id, "+12025550145", "Reference", listOf(local), 1)
            assertTrue(store.save(request))
            assertTrue(store.locked { store.claim(request) })
            // Accepted was returned, but the app stopped before deleting the image.
            assertTrue(context.markSharedComposerImagesForDiscard(listOf(local), terminal = true))
            if (completedBeforeRestart) store.markComposeCompleted(id)
            val recovered = ComposerStore(context)
            assertFalse(copy.exists())
            assertFalse(discardMarkers().any { it.readText() == copy.name })
            assertTrue(recovered.isUnknown(id))
            var handoffs = 0
            val dispatcher = MessageDispatcher({ true }, { true }) {
                handoffs++
                DispatchOutcome.Accepted()
            }
            assertTrue(recovered.submit(request, dispatcher) is DispatchOutcome.Rejected)
            assertEquals(0, handoffs)
        } finally { store.clear(id); source.delete() }
    }

    @Test fun interruptedClearCommitReapsTheUnreferencedPrivateCopy() {
        val source = source()
        val id = id()
        val store = ComposerStore(context)
        try {
            val local = requireNotNull(context.captureSharedComposerImages(listOf(uri(source)))).single()
            val copy = copy(local.lastPathSegment!!)
            assertTrue(store.save(OutgoingMessage(id, "+12025550145", "Reference", listOf(local), -1)))
            assertTrue(context.markSharedComposerImagesForDiscard(listOf(local)))
            assertTrue(store.prefs.edit().remove(id.toString()).commit())
            // Simulated process death after preferences commit, before file deletion.
            ComposerStore(context)
            assertFalse(copy.exists())
            assertFalse(discardMarkers().any { it.readText() == copy.name })
        } finally { store.clear(id); source.delete() }
    }

    @Test fun interruptedReplacementKeepsReferencedCopyUntilTheCommit() {
        val source = source()
        val id = id()
        val store = ComposerStore(context)
        try {
            val local = requireNotNull(context.captureSharedComposerImages(listOf(uri(source)))).single()
            val copy = copy(local.lastPathSegment!!)
            assertTrue(store.save(OutgoingMessage(id, "+12025550145", "Reference", listOf(local), -1)))
            assertTrue(context.markSharedComposerImagesForDiscard(listOf(local)))
            // A previous process marked the old attachment but died before committing the replacement.
            ComposerStore(context)
            assertTrue(copy.isFile)
            assertEquals(listOf(local), ComposerStore(context).load(id)?.attachmentUris)
            assertFalse(discardMarkers().any { it.readText() == copy.name })

            assertTrue(context.markSharedComposerImagesForDiscard(listOf(local)))
            val changed = JSONObject(store.prefs.getString(id.toString(), null)!!).put("uris", JSONArray())
            assertTrue(store.prefs.edit().putString(id.toString(), changed.toString()).commit())
            ComposerStore(context)
            assertFalse(copy.exists())
        } finally { store.clear(id); source.delete() }
    }

    private fun source(): File = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }
    private fun id(): Long = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    private fun copy(name: String) = File(context.filesDir, "composer-shares/$name")
    private fun discardMarkers() = File(context.filesDir, "composer-shares").listFiles().orEmpty()
        .filter { it.name.startsWith("discard-") && it.name.endsWith(".journal") }
}
