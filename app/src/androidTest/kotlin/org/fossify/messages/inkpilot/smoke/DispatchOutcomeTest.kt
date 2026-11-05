package org.fossify.messages.inkpilot.smoke

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.messaging.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic dispatcher only: no SmsManager, radio, or provider writes. */
@RunWith(AndroidJUnit4::class)
class DispatchOutcomeTest {
    private val request = OutgoingMessage(7654321, "+12025550123", "human text é", listOf(Uri.parse("content://synthetic/missing")), 42)

    @Test fun unreadableUriNeverReachesTransportAndDraftSurvives() {
        var sends = 0
        val outcome = MessageDispatcher({ false }, { true }) { sends++; DispatchOutcome.Accepted() }.submit(request)
        assertTrue(outcome is DispatchOutcome.Rejected)
        assertEquals(0, sends)
        assertPreserved(outcome)
    }

    @Test fun expiredOrMissingUriAfterReopenNeverFallsBackToText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ComposerStore(context)
        var sends = 0
        try {
            assertTrue(store.save(request))
            val reopened = requireNotNull(ComposerStore(context).load(request.threadId))
            val result = MessageDispatcher(
                { uri -> context.contentResolver.openInputStream(uri)?.use { it.read(); true } ?: false },
                { true }
            ) { sends++; DispatchOutcome.Accepted() }.submit(reopened)
            assertTrue(result is DispatchOutcome.Rejected)
            store.complete(request.threadId, result)
            assertEquals(request, ComposerStore(context).load(request.threadId))
            assertEquals(0, sends)
        } finally { store.clear(request.threadId) }
    }

    @Test fun deniedPermissionDoesNotSend() {
        val outcome = MessageDispatcher({ true }, { false }) { error("must not send") }.submit(request)
        assertTrue(outcome is DispatchOutcome.Rejected)
        assertPreserved(outcome)
    }

    @Test fun transportThrowIsUnknownAndExactRequestIsForwarded() {
        var observed: OutgoingMessage? = null
        val outcome = MessageDispatcher({ true }, { true }) { observed = it; throw IllegalStateException("after handoff") }.submit(request)
        assertEquals(request, observed)
        assertTrue(outcome is DispatchOutcome.Unknown)
        assertPreserved(outcome)
    }

    @Test fun onlyAcceptanceClearsDurableComposer() {
        val store = ComposerStore(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            assertTrue(store.save(request))
            store.complete(request.threadId, DispatchOutcome.Accepted())
            assertNull(store.load(request.threadId))
        } finally { store.clear(request.threadId) }
    }

    private fun assertPreserved(outcome: DispatchOutcome) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ComposerStore(context)
        try {
            assertTrue(store.save(request))
            store.complete(request.threadId, outcome)
            assertEquals(request, ComposerStore(context).load(request.threadId))
        } finally { store.clear(request.threadId) }
    }
}
