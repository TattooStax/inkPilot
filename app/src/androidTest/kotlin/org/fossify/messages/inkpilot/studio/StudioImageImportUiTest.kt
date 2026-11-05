package org.fossify.messages.inkpilot.studio

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioThreadAdapter
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageAttachment
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class StudioImageImportUiTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun scopedGalleryImportReturnsToItsOwningPanelAndPreservesLink() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = "synthetic-import-ui-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        val contact = repository.upsertContact("+12025550145", "Synthetic UI client")
        val source = File(File(context.cacheDir, "compressed").apply { mkdirs() }, "synthetic-ui-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", source)
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        var original: File? = null
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val panels = StudioPanels(activity, Executor { it.run() }, { error(it) }, repository)
                    // The root albums panel exists too; results must reach the linked panel.
                    panels.albums()
                    val linked = panels.linkedImages(contact.id, 73)
                    activity.setContentView(linked)
                    views(linked).filterIsInstance<TextView>().single { it.text == "Import reference image" }.performClick()
                    assertTrue(panels.onActivityResult(StudioPanels.IMPORT_IMAGE, Activity.RESULT_OK, Intent().setData(uri)))
                    val store = StudioAlbumStore(repository)
                    val saved = store.linkedImages(contact.id, 73).single()
                    original = StudioImageFiles(context, store).originalFile(saved)
                    assertTrue(views(linked).filterIsInstance<TextView>().any { it.text.toString() == saved.displayName })
                    assertTrue(store.linkedImages(contactId = "unrelated").isEmpty())
                    repository.database.rawQuery("SELECT source_origin,source_id FROM image_links", null).use {
                        assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
                    }
                }
            }
        } finally {
            val ownedOriginal = original ?: runCatching {
                val gallery = StudioAlbumStore(repository)
                gallery.linkedImages(contact.id, 73).singleOrNull()
                    ?.let { StudioImageFiles(context, gallery).originalFile(it) }
            }.getOrNull()
            instrumentation.removeMonitor(monitor); repository.close(); context.deleteDatabase(name)
            source.delete(); ownedOriginal?.delete()
        }
    }

    @Test fun messageImageControlPassesTypedMmsKeyThreadAndOriginalAttachment() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val attachment = Attachment(null, 41, "content://synthetic/part", "image/png", 8, 8, "Synthetic image")
                val message = Message(41, "Synthetic", 1, 0, arrayListOf(), 1, true, 73, true,
                    MessageAttachment(41, "", arrayListOf(attachment)), "", "", "", -1)
                var invoked = false
                val adapter = StudioThreadAdapter(activity) { key, threadId, original ->
                    assertEquals(MessageKey(MessageOrigin.MMS, 41), key); assertEquals(73L, threadId)
                    assertSame(attachment, original); invoked = true
                }
                adapter.append(listOf(message))
                val row = adapter.onCreateViewHolder(android.widget.LinearLayout(activity), 0)
                adapter.onBindViewHolder(row, 0)
                views(row.body).filterIsInstance<TextView>().single { it.text == "Save to Client References" }.performClick()
                assertTrue(invoked)
                val scheduled = StudioThreadAdapter(activity)
                scheduled.append(listOf(message.copy(isScheduled = true)))
                val scheduledRow = scheduled.onCreateViewHolder(android.widget.LinearLayout(activity), 0)
                scheduled.onBindViewHolder(scheduledRow, 0)
                assertFalse(views(scheduledRow.body).filterIsInstance<TextView>().any { it.text == "Save to Client References" })
            }
        }
    }
}
