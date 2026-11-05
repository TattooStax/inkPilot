package org.fossify.messages.inkpilot.studio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executor
import org.fossify.messages.inkpilot.data.StudioRepository

/** Body-only panels; host owns navigation, repository lifetime, and activity results. */
class StudioPanels(
    activity: AppCompatActivity,
    executor: Executor,
    onError: (String) -> Unit,
    private val repository: StudioRepository,
    savedImport: Bundle? = null,
    private val onImageSaved: () -> Unit = {},
) {
    private val host = StudioPanelHost(activity, executor, onError)
    private var importTarget: StudioAlbumsPanel? = null
    private var importScope: ImportScope? = ImportScope.restore(savedImport)
    private fun albumPanel() = StudioAlbumsPanel(host, StudioAlbumStore(repository),
        requestImport = { panel, contactId, threadId ->
            importTarget = panel
            importScope = ImportScope(contactId, threadId)
        }, onImageSaved = onImageSaved)
    private val albums = albumPanel()
    private val calendar = StudioCalendarPanel(host, StudioCalendarStore(repository))

    fun albums(): View = albums.view()
    fun linkedImages(contactId: String? = null, threadId: Long? = null): View = albumPanel().linkedView(contactId, threadId)

    internal fun importMmsOriginal(uri: android.net.Uri, link: StudioImageLink, onSaved: () -> Unit) {
        host.load({ StudioImageFiles(host.activity.applicationContext, StudioAlbumStore(repository)).importMmsOriginal(uri, link) }) { onSaved() }
    }

    fun calendar(): View = calendar.view()

    fun saveImportState(): Bundle? = importScope?.save()

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != IMPORT_IMAGE) return false
        val scope = importScope
        val target = importTarget ?: scope?.let { saved -> albumPanel().apply {
            if (saved.contactId != null || saved.threadId != null)
                restoreImportScope(saved.contactId, saved.threadId)
        } }
        importTarget = null; importScope = null
        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri == null || target == null) host.onError("Image selection was interrupted. Choose the image again.")
            else target.acceptImage(uri)
        }
        return true
    }

    private data class ImportScope(val contactId: String?, val threadId: Long?) {
        fun save() = Bundle().apply {
            putBoolean("pending", true)
            putString("contact", contactId)
            threadId?.let { putLong("thread", it) }
        }
        companion object {
            fun restore(saved: Bundle?): ImportScope? = saved?.takeIf { it.getBoolean("pending") }?.let {
                ImportScope(it.getString("contact"), if (it.containsKey("thread")) it.getLong("thread") else null)
            }
        }
    }

    internal companion object { const val IMPORT_IMAGE = 7301 }
}
