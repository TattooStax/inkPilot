package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

/** A queued delete must not be followed by the old editor's Save action. */
@RunWith(AndroidJUnit4::class)
class ContactForgetUiTest {
    @Test fun immediateOldSaveCannotRestoreForgottenMemory() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlock = context.baseConfig.lastUnlockTimestampMs
        context.appLockManager.unlock()
        val name = "forget-ui-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        val contact = repository.upsertContact("+12025550142", "Synthetic forget client")
        repository.saveContactEdit(contact.id, "Old private memory", false, "Staff note")
        val queue = ArrayDeque<Runnable>()
        val workQueue = Executor { queue.addLast(it) }
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(ContactPanel(activity, repository,
                        StudioWork(activity, workQueue), {}).view())
                    queue.removeFirst().run() // Contact list loaded.
                    val title = findText(activity.window.decorView, "Synthetic forget client")
                    assertTrue((title?.parent as? View)?.performClick() == true)
                    queue.removeFirst().run() // Contact memory loaded; editor opened.
                }
                SmokeViews.clickText("Forget memory")
                SmokeViews.clickText("Forget")
                val save = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Save")
                    ?.firstOrNull { it.text?.toString() == "Save" && it.isVisibleToUser && it.isClickable }
                // Exercise the stale action if a regression leaves the edit dialog open.
                save?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                assertNull("Confirmed forget retires the old Save action", save)
                scenario.onActivity {
                    assertFalse(queue.isEmpty())
                    while (!queue.isEmpty()) queue.removeFirst().run()
                    assertEquals("", repository.contact(contact.id)?.memory)
                    assertNull(repository.memoryEntry(contact.id))
                }
            }
        } finally {
            repository.close()
            context.deleteDatabase(name)
            context.baseConfig.lastUnlockTimestampMs = priorUnlock
        }
    }

    private fun findText(view: View, value: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == value }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), value) }
        else -> null
    }
}
