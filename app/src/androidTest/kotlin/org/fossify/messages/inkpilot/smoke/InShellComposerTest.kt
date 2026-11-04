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
        val route = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550101012"), "retained text", listOf(Uri.parse("content://synthetic/retained")))
        ComposerStore(context).clear(route.composerId)
        androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openRoute(route)
                assertNotNull(activity.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_navigation))
                fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                    if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
                val editor = children(activity.window.decorView).filterIsInstance<android.widget.EditText>().first { it.hint == "Message" }
                editor.setText("staff edit preserved")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_home))
                assertEquals("staff edit preserved", ComposerStore(activity).load(route.composerId)?.body)
                assertEquals(listOf(Uri.parse("content://synthetic/retained")), ComposerStore(activity).load(route.composerId)?.attachmentUris)
            }
        }
        ComposerStore(context).clear(route.composerId)
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
    @Test fun distinctComposeRoutesNeverRestoreAnotherRecipientOrMedia() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550101111"), "A private caption", listOf(Uri.parse("content://synthetic/A")))
        val b = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550102222"), "B caption", listOf(Uri.parse("content://synthetic/B")))
        assertNotEquals(a.composerId, b.composerId)
        androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.openRoute(a); activity.openRoute(b) }
            scenario.recreate()
            scenario.onActivity { activity ->
                fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                    if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
                val editors = children(activity.window.decorView).filterIsInstance<android.widget.EditText>()
                assertEquals(b.recipients.single(), editors.first { it.hint == "Recipients separated by |" }.text.toString())
                assertEquals(b.body, editors.first { it.hint == "Message" }.text.toString())
                val stored = ComposerStore(activity).load(b.composerId)!!
                assertEquals(b.recipients.single(), stored.recipient)
                assertEquals(b.body, stored.body)
                assertEquals(b.mediaUris, stored.attachmentUris)
                assertEquals(a.mediaUris, ComposerStore(activity).load(a.composerId)?.attachmentUris)
            }
        }
        ComposerStore(context).clear(a.composerId); ComposerStore(context).clear(b.composerId)
    }

    @Test fun concurrentHostsClaimOneCarrierHandoffEvenWhenAccepted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -(java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val request = ComposerStore(context).getOrCreate(OutgoingMessage(id, "+15550103333", "race", emptyList(), 3))
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<DispatchOutcome> {
                    val store = ComposerStore(context)
                    val sameDraft = store.getOrCreate(request)
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    store.submit(sameDraft, MessageDispatcher({ true }, { true }) { calls.incrementAndGet(); DispatchOutcome.Accepted() })
                }
            }.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertEquals(1, results.count { it is DispatchOutcome.Accepted })
            assertEquals(1, results.count { it is DispatchOutcome.Rejected })
            assertNull(ComposerStore(context).load(id))
            assertTrue(ComposerStore(context).submit(request, MessageDispatcher({ true }, { true }) { fail("Recreated host must not replay"); DispatchOutcome.Accepted() }) is DispatchOutcome.Rejected)
        } finally { pool.shutdownNow(); ComposerStore(context).clear(id) }
    }

    @Test fun preclaimExceptionAllowsRetryButDurableUnknownDoesNot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ComposerStore(context)
        val request = store.getOrCreate(OutgoingMessage(-81015L, "+15550104444", "kept", emptyList(), 3))
        val failed = org.fossify.messages.inkpilot.ui.prepareComposerSend(store, request) { throw SecurityException("Synthetic SIM lookup revocation") }
        assertTrue(failed.isFailure)
        assertTrue(org.fossify.messages.inkpilot.ui.composerCanRetry(store, request))
        assertTrue(store.submit(request, MessageDispatcher({ true }, { true }) { DispatchOutcome.Unknown("fixture") }) is DispatchOutcome.Unknown)
        assertFalse(org.fossify.messages.inkpilot.ui.composerCanRetry(ComposerStore(context), request))
        store.clear(request.threadId)
    }

}
