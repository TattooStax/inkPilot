package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Build
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** A delayed import callback must preserve the tab staff chose after confirming it. */
@RunWith(AndroidJUnit4::class)
class VCardImportRouteUiTest {
    @Test fun importedCardDoesNotSwitchAwayFromNewerCalendarTab() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlock = context.baseConfig.lastUnlockTimestampMs
        context.appLockManager.unlock()
        val phone = "+1202555" + (1000..9999).random()
        val file = File(context.cacheDir, "attachments/queued-card.vcf")
        file.parentFile!!.mkdirs()
        file.writeText("BEGIN:VCARD\nVERSION:3.0\nFN:Queued Client\nTEL:$phone\nEND:VCARD\n")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val gate = CountDownLatch(1)
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openRoute(StudioRoute.Attachment(uri, "text/vcard", "queued-card.vcf"))
                }
                SmokeViews.assertText("Add to Studio contacts · $phone")
                scenario.onActivity { activity ->
                    val executor = StudioHomeActivity::class.java.getDeclaredField("executor").apply {
                        isAccessible = true
                    }.get(activity) as ExecutorService
                    executor.execute { gate.await(10, TimeUnit.SECONDS) }
                }
                SmokeViews.clickText("Add to Studio contacts · $phone")
                SmokeViews.clickText("Add")
                scenario.onActivity { activity -> activity.openRoute(StudioRoute.Tab(StudioTab.CALENDAR)) }
                gate.countDown()
                StudioRepository(context).use { repository ->
                    SmokeViews.await("queued card import") {
                        repository.contacts().firstOrNull { it.number == phone }
                    }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals("Calendar", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
                }
            }
        } finally {
            gate.countDown()
            file.delete()
            context.baseConfig.lastUnlockTimestampMs = priorUnlock
        }
    }
}
