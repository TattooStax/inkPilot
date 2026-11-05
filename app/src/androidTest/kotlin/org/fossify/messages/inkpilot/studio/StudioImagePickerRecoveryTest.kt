package org.fossify.messages.inkpilot.studio

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioImagePickerRecoveryTest {
    @Test fun linkedPickerResultKeepsItsClientAndThreadAfterPanelRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val databaseName = "picker-recovery-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, databaseName)
        val contact = repository.upsertContact("+12025550142", "Synthetic picker client")
        val source = File(File(context.cacheDir, "compressed").apply { mkdirs() }, "picker-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", source)
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        var original: File? = null
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val first = StudioPanels(activity, Executor { it.run() }, { error(it) }, repository)
                    val linked = first.linkedImages(contact.id, 73)
                    activity.setContentView(linked)
                    views(linked).filterIsInstance<TextView>()
                        .single { it.text == "Import reference image" }.performClick()
                    val saved = requireNotNull(first.saveImportState())
                    val restored = StudioPanels(activity, Executor { it.run() }, { error(it) }, repository, saved)
                    assertTrue(restored.onActivityResult(StudioPanels.IMPORT_IMAGE, Activity.RESULT_OK,
                        Intent().setData(uri)))
                    val store = StudioAlbumStore(repository)
                    val image = store.linkedImages(contact.id, 73).single()
                    original = StudioImageFiles(context, store).originalFile(image)
                    assertTrue(original!!.isFile)
                    assertNull(restored.saveImportState())
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            repository.close(); context.deleteDatabase(databaseName)
            source.delete(); original?.delete()
        }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
