package org.fossify.messages.inkpilot.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.R
import org.fossify.messages.messaging.ComposerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationEntryRouteTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun alertTapReusesHomeAndKeepsUnsentComposerAcrossDraftsAndCalendar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lock = context.appLockManager
        val wasLocked = lock.isLocked()
        lock.unlock()
        val route = StudioRoute.Compose(listOf("+12025550141"), "Unsent client note", emptyList())
        val launch = Intent(context, StudioHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(ExternalRouteParser.ROUTE, route.encode())
        try {
            ActivityScenario.launch<StudioHomeActivity>(launch).use { scenario ->
                lateinit var original: StudioHomeActivity
                scenario.onActivity { original = it }
                awaitTitle(original, "New message")
                assertEquals(route.body, ComposerStore(context).load(route.composerId)?.body)

                sendPanelIntent("drafts", 19101)
                awaitTitle(original, "Drafts")
                scenario.onActivity { assertSame(original, it) }
                instrumentation.runOnMainSync { original.onBackPressedDispatcher.onBackPressed() }
                awaitTitle(original, "New message")

                sendPanelIntent("calendar", 19102)
                awaitTitle(original, "Calendar")
                scenario.onActivity { assertSame(original, it) }
                instrumentation.runOnMainSync {
                    assertTrue(original.findViewById<android.view.View>(R.id.inkpilot_tab_sms).performClick())
                }
                awaitTitle(original, "New message")
                assertEquals(route.body, ComposerStore(context).load(route.composerId)?.body)
            }
        } finally {
            ComposerStore(context).clear(route.composerId)
            if (wasLocked) lock.lock() else lock.unlock()
        }
    }

    private fun sendPanelIntent(panel: String, requestCode: Int) {
        val pending = PendingIntent.getActivity(context, requestCode, studioPanelIntent(context, panel),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { pending.send() } finally { pending.cancel() }
    }

    private fun awaitTitle(activity: StudioHomeActivity, expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var title = ""
        do {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                title = activity.findViewById<TextView>(R.id.inkpilot_title)?.text?.toString().orEmpty()
            }
            if (title == expected) return
            Thread.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, title)
    }
}
