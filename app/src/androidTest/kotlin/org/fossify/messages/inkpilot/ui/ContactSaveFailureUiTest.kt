package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
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
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

/** Failed storage keeps staff's typed memory available for a retry. */
@RunWith(AndroidJUnit4::class)
class ContactSaveFailureUiTest {
    @Test fun failedSaveKeepsEditorAndTypedMemoryThenRetrySucceeds() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlock = context.baseConfig.lastUnlockTimestampMs
        context.appLockManager.unlock()
        val name = "save-failure-ui-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        val contact = repository.upsertContact("+12025550146", "Synthetic save client")
        val queue = ArrayDeque<Runnable>()
        val workQueue = Executor { queue.addLast(it) }
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(ContactPanel(activity, repository,
                        StudioWork(activity, workQueue), {}).view())
                    queue.removeFirst().run()
                    val title = findText(activity.window.decorView, "Synthetic save client")
                    assertTrue((title?.parent as? View)?.performClick() == true)
                    queue.removeFirst().run()
                    repository.database.execSQL("CREATE TRIGGER reject_edit BEFORE UPDATE ON studio_contacts " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
                }
                val memory = memoryNode(context)
                val text = "Careful: keep this staff note"
                assertTrue(memory.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }))
                SmokeViews.clickText("Save")
                scenario.onActivity {
                    assertEquals(1, queue.size)
                    queue.removeFirst().run()
                    assertEquals("", repository.contact(contact.id)?.memory)
                }
                assertEquals(text, memoryNode(context).text?.toString())
                SmokeViews.assertText("Save")
                scenario.onActivity { repository.database.execSQL("DROP TRIGGER reject_edit") }
                SmokeViews.clickText("Save")
                scenario.onActivity {
                    assertEquals(1, queue.size)
                    queue.removeFirst().run()
                    assertEquals(text, repository.contact(contact.id)?.memory)
                }
            }
        } finally {
            repository.close()
            context.deleteDatabase(name)
            context.baseConfig.lastUnlockTimestampMs = priorUnlock
        }
    }

    private fun memoryNode(context: Context): AccessibilityNodeInfo = SmokeViews.await("visible contact memory editor") {
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(context.packageName + ":id/inkpilot_contact_memory")
            ?.firstOrNull { it.isVisibleToUser }
    }

    private fun findText(view: View, value: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == value }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), value) }
        else -> null
    }
}
