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
    repository: StudioRepository,
) {
    private val host = StudioPanelHost(activity, executor, onError)
    private val albums = StudioAlbumsPanel(host, StudioAlbumStore(repository))
    private val calendar = StudioCalendarPanel(host, StudioCalendarStore(repository))

    fun albums(): View = albums.view()
    fun calendar(): View = calendar.view()

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != IMPORT_IMAGE) return false
        if (resultCode == Activity.RESULT_OK) data?.data?.let { albums.acceptImage(it) }
        return true
    }

    internal companion object { const val IMPORT_IMAGE = 7301 }
}
