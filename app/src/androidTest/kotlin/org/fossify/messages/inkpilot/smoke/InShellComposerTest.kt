package org.fossify.messages.inkpilot.smoke

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.fossify.messages.messaging.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic dispatcher only: this suite never reaches a carrier. */
class InShellComposerTest {
    @Test fun composeKeepsShellAndOriginalTextOnRotation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ComposerStore(context).clear(0)
        androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openRoute(org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550101012"), "retained text", listOf(Uri.parse("content://synthetic/retained"))))
                assertNotNull(activity.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_navigation))
                fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                    if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
                val editor = children(activity.window.decorView).filterIsInstance<android.widget.EditText>().first { it.hint == "Message" }
                editor.setText("staff edit preserved")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_home))
                assertEquals("staff edit preserved", ComposerStore(activity).load(0)?.body)
                assertEquals(listOf(Uri.parse("content://synthetic/retained")), ComposerStore(activity).load(0)?.attachmentUris)
            }
        }
        ComposerStore(context).clear(0)
    }

    @Test fun failedMmsRetainsOriginalAcrossStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ComposerStore(context)
        val id = -81010L
        store.clear(id)
        val request = OutgoingMessage(id, "+15550101010", "original human text", listOf(Uri.parse("content://synthetic/image")), 12)
        val outcome = store.submit(request, MessageDispatcher({ false }, { true }) { fail("Unreadable MMS must not hand off"); DispatchOutcome.Accepted() })
        assertTrue(outcome is DispatchOutcome.Rejected)
        assertEquals(request, ComposerStore(context).load(id))
        store.clear(id)
    }
    @Test fun unknownDoesNotRetryOrDowngradeToText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -81011L
        val store = ComposerStore(context)
        store.clear(id)
        val request = OutgoingMessage(id, "+15550101011", "caption", listOf(Uri.parse("content://synthetic/image")), 13)
        var calls = 0
        val dispatcher = MessageDispatcher({ true }, { true }) { calls++; DispatchOutcome.Unknown("claim") }
        assertTrue(store.submit(request, dispatcher) is DispatchOutcome.Unknown)
        assertEquals(request, ComposerStore(context).load(id))
        assertTrue(ComposerStore(context).submit(request, dispatcher) is DispatchOutcome.Rejected)
        assertEquals(1, calls)
        store.clear(id)
    }
}
