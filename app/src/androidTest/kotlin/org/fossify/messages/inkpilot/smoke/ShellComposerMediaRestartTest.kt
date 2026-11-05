package org.fossify.messages.inkpilot.smoke

import android.net.Uri
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.R
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.OutgoingMessage
import org.fossify.messages.messaging.inShellMmsRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val MEDIA_THREAD = -7_654_322L
private val mediaUri = Uri.parse("content://org.fossify.messages.task5.revocable-preview/image.png")
private val mediaRequest = OutgoingMessage(MEDIA_THREAD, "+12025550123", "Human caption with image",
    listOf(mediaUri), -1, "shell-media-restart")
private const val PHASE_PREFS = "shell-media-restart-proof"

private fun descendants(view: View): List<View> = listOf(view) +
    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

private fun assertRestoredComposer(activity: StudioHomeActivity) {
    val views = descendants(activity.window.decorView)
    assertNotNull(activity.findViewById<View>(R.id.inkpilot_home))
    assertNotNull(activity.findViewById<View>(R.id.inkpilot_navigation))
    assertEquals(mediaRequest.recipient, views.filterIsInstance<EditText>()
        .single { it.hint == "Recipients separated by |" }.text.toString())
    assertEquals(mediaRequest.body, views.filterIsInstance<EditText>()
        .single { it.hint == "Message" }.text.toString())
    assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().startsWith("Remove attachment:") })
    assertEquals(mediaRequest, ComposerStore(activity).load(MEDIA_THREAD))
}

/** Run, force-stop the disposable target package, then run ShellComposerMediaVerifyTest. */
@RunWith(AndroidJUnit4::class)
class ShellComposerMediaPrepareTest {
    @Test fun commitReadableMediaAndVisibleShellComposer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ComposerStore(context)
        store.clear(MEDIA_THREAD)
        context.contentResolver.call(mediaUri, "cleanup", null, null)
        context.contentResolver.call(mediaUri, "grant", null, null)
        context.contentResolver.openInputStream(mediaUri)!!.use { assertTrue(it.read() >= 0) }
        assertTrue(store.save(mediaRequest))
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openRoute(StudioRoute.Compose(listOf(mediaRequest.recipient), mediaRequest.body,
                    listOf(mediaUri), MEDIA_THREAD))
                assertRestoredComposer(activity)
            }
        }
        assertTrue(context.getSharedPreferences(PHASE_PREFS, 0).edit().putInt("pid", Process.myPid()).commit())
    }
}

/** Verifies a different target process and a failed read with the same private composer state. */
@RunWith(AndroidJUnit4::class)
class ShellComposerMediaVerifyTest {
    @Test fun revokedMediaAfterProcessDeathCannotSendTextAlone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ComposerStore(context)
        try {
            val previousPid = context.getSharedPreferences(PHASE_PREFS, 0).getInt("pid", -1)
            assertNotEquals("Prepare phase must run first", -1, previousPid)
            assertNotEquals("The target process must restart", previousPid, Process.myPid())
            assertEquals(mediaRequest, store.load(MEDIA_THREAD))
            context.contentResolver.call(mediaUri, "revoke", null, null)
            assertEquals("image/png", context.contentResolver.getType(mediaUri))
            assertEquals("Attachment is unreadable", context.inShellMmsRejection(listOf(mediaUri)))
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openRoute(StudioRoute.Compose(listOf(mediaRequest.recipient), mediaRequest.body,
                        listOf(mediaUri), MEDIA_THREAD))
                    assertRestoredComposer(activity)
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .single { it.text.toString() == "Send message" }.apply {
                            assertTrue(isEnabled)
                            assertTrue(performClick())
                        }
                }
                SmokeViews.await("revoked media rejection") {
                    var visible = false
                    scenario.onActivity { activity ->
                        visible = descendants(activity.window.decorView)
                            .filterIsInstance<TextView>().any { it.text.toString() == "Attachment is unreadable" }
                    }
                    visible.takeIf { it }
                }
                scenario.onActivity { activity ->
                    assertRestoredComposer(activity)
                    assertFalse(store.isUnknown(MEDIA_THREAD))
                    assertFalse(store.wasSubmitted(mediaRequest))
                }
            }
        } finally {
            store.clear(MEDIA_THREAD)
            context.contentResolver.call(mediaUri, "cleanup", null, null)
            context.getSharedPreferences(PHASE_PREFS, 0).edit().clear().commit()
        }
    }
}
