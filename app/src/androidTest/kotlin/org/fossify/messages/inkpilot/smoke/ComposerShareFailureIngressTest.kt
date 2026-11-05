package org.fossify.messages.inkpilot.smoke

import android.content.ClipData
import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.inkpilot.ui.ExternalRouteParser
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.decodeRoute
import org.fossify.messages.messaging.ComposerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComposerShareFailureIngressTest {
    @Test fun unsupportedShareKeepsCaptionVisibleAndBlocksSendUntilReattach() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.txt")
        source.parentFile!!.mkdirs()
        source.writeText("unsupported media")
        val body = "Caption ${UUID.randomUUID()}"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
        var home: StudioHomeActivity? = null
        var id = 0L
        try {
            context.startActivity(Intent(context, NewConversationActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Unsupported reference", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            val deadline = SystemClock.uptimeMillis() + 10_000L
            do {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    home = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<StudioHomeActivity>().firstOrNull { activity ->
                            (activity.intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()
                                as? StudioRoute.Compose)
                                ?.let { it.body == body && it.shareCopyFailed } == true
                        }
                }
                if (home != null) break
                Thread.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            assertNotNull(home)
            val route = home!!.intent.getBundleExtra(ExternalRouteParser.ROUTE)!!.decodeRoute()
                as StudioRoute.Compose
            id = route.composerId
            assertEquals(listOf(uri), route.mediaUris)
            instrumentation.runOnMainSync {
                fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                    if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
                    else emptyList()
                val labels = children(home!!.window.decorView).filterIsInstance<TextView>()
                assertTrue(labels.any { it.text.toString().contains("Remove it and attach again") })
                assertFalse(labels.single { it.text.toString() == "Send message" }.isEnabled)
                assertEquals(body, ComposerStore(home!!).load(id)?.body)
            }
        } finally {
            instrumentation.runOnMainSync { home?.finish() }
            if (id != 0L) ComposerStore(context).clear(id)
            source.delete()
        }
    }
}
