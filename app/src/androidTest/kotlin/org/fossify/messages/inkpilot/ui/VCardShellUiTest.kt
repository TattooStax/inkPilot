package org.fossify.messages.inkpilot.ui

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.R
import org.fossify.messages.activities.VCardViewerActivity
import org.fossify.messages.helpers.EXTRA_VCARD_URI
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VCardShellUiTest {
    @Test fun historicalCardOpensBoundedPreviewInsideShellWithoutImportingIt() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val lock = context.appLockManager
        val wasLocked = lock.isLocked()
        val file = File(context.cacheDir, "attachments/synthetic-card.vcf")
        file.parentFile!!.mkdirs()
        file.writeText("BEGIN:VCARD\nVERSION:3.0\nFN:Maya Ortiz\nTEL:+12025550142\nEND:VCARD\n")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val monitor = instrumentation.addMonitor(StudioHomeActivity::class.java.name, null, false)
        var home: StudioHomeActivity? = null
        try {
            lock.unlock()
            context.startActivity(Intent(context, VCardViewerActivity::class.java).apply {
                putExtra(EXTRA_VCARD_URI, uri)
                clipData = ClipData.newRawUri("Synthetic card", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            home = monitor.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(home)
            SmokeViews.assertText("Maya Ortiz")
            SmokeViews.assertText("+12025550142")
            SmokeViews.assertText("Add to Studio contacts · +12025550142")
            instrumentation.runOnMainSync {
                val shell = requireNotNull(home)
                assertNotNull(shell.findViewById<android.view.View>(R.id.inkpilot_navigation))
                shell.onBackPressedDispatcher.onBackPressed()
                assertEquals("SMS",
                    shell.findViewById<android.widget.TextView>(R.id.inkpilot_title).text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { home?.finish() }
            instrumentation.removeMonitor(monitor)
            file.delete()
            if (wasLocked) lock.lock() else lock.unlock()
        }
    }
}
