package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** A completed contact read must not open a dialog over a newer tab. */
@RunWith(AndroidJUnit4::class)
class ContactOpenRouteUiTest {
    @Test fun delayedContactReadLeavesNewerPhoneTabAlone() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlock = context.baseConfig.lastUnlockTimestampMs
        context.appLockManager.unlock()
        val name = "Queued contact ${UUID.randomUUID()}"
        val phone = "+1202555" + (1000..9999).random()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        StudioRepository(context).use { repository ->
            val contact = repository.upsertContact(phone, name)
            try {
                ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                    scenario.onActivity { it.openRoute(StudioRoute.Tab(StudioTab.CONTACTS)) }
                    SmokeViews.assertText(name)
                    scenario.onActivity { activity ->
                        executor(activity).execute {
                            started.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        assertTrue((findText(activity.window.decorView, name)?.parent as? View)
                            ?.performClick() == true)
                        activity.openRoute(StudioRoute.Tab(StudioTab.PHONE))
                        executor(activity).execute { completed.countDown() }
                    }
                    release.countDown()
                    assertTrue(completed.await(10, TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertEquals("Phone", activity.findViewById<TextView>(R.id.inkpilot_title).text.toString())
                    }
                    assertNull(instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId(context.packageName + ":id/inkpilot_contact_memory")
                        ?.firstOrNull { it.isVisibleToUser })
                }
            } finally {
                release.countDown()
                repository.database.delete("contact_aliases", "contact_id = ?", arrayOf(contact.id))
                repository.database.delete("studio_contacts", "id = ?", arrayOf(contact.id))
                context.baseConfig.lastUnlockTimestampMs = priorUnlock
            }
        }
    }

    private fun executor(activity: StudioHomeActivity) =
        StudioHomeActivity::class.java.getDeclaredField("executor").apply {
            isAccessible = true
        }.get(activity) as ExecutorService

    private fun findText(view: View, value: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == value }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), value) }
        else -> null
    }
}
