package org.fossify.messages.inkpilot.studio

import android.app.Activity
import android.content.Intent
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
) {
    private val host = StudioPanelHost(activity, executor, onError)
    private var importTarget: StudioAlbumsPanel? = null
    private fun albumPanel() = StudioAlbumsPanel(host, StudioAlbumStore(repository)) { importTarget = it }
    private val albums = albumPanel()
    private val calendar = StudioCalendarPanel(host, StudioCalendarStore(repository))

    fun albums(): View = albums.view()
    fun linkedImages(contactId: String? = null, threadId: Long? = null): View = albumPanel().linkedView(contactId, threadId)

    internal fun importMmsOriginal(uri: android.net.Uri, link: StudioImageLink, onSaved: () -> Unit) {
        host.load({ StudioImageFiles(host.activity.applicationContext, StudioAlbumStore(repository)).importMmsOriginal(uri, link) }) { onSaved() }
    }

    fun calendar(): View = calendar.view()

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != IMPORT_IMAGE) return false
        val target = importTarget; importTarget = null
        if (resultCode == Activity.RESULT_OK) data?.data?.let { target?.acceptImage(it) }
        return true
    }

    internal companion object { const val IMPORT_IMAGE = 7301 }
}
