package org.fossify.messages.inkpilot.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.ui.composerCanRetry
import org.fossify.messages.inkpilot.ui.initialComposerStatus
import org.fossify.messages.inkpilot.ui.onUnknownComposerOutcome
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.MessageDispatcher
import org.fossify.messages.messaging.OutgoingMessage
import org.fossify.messages.messaging.submit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Synthetic carrier only: the failure is between the two claim writes. */
@RunWith(AndroidJUnit4::class)
class ComposerClaimRecoveryTest {
    @Test fun secondMarkerFailureCannotStrandAnInvisibleUnretryableDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val request = OutgoingMessage(id, "+12025550123", "staff wording retained", emptyList(), 1)
        val store = ComposerStore(context)
        store.clear(id)
        var handoffs = 0
        val dispatcher = MessageDispatcher({ true }, { true }) {
            handoffs++
            DispatchOutcome.Accepted()
        }
        try {
            assertTrue(store.save(request))
            val pending = store.pendingFile(id)
            store.claimMarker = { file ->
                if (file == pending) throw IOException("synthetic second marker write failure")
                store.durableMarker(file)
            }
            assertTrue(runCatching { store.submit(request, dispatcher) }.isFailure)
            assertFalse(pending.exists())
            val reopened = ComposerStore(context)
            assertTrue(reopened.wasSubmitted(request))
            assertEquals(request, reopened.load(id))
            assertTrue(reopened.isUnknown(id))
            assertTrue(initialComposerStatus(reopened, id).contains("outcome unknown"))
            val status = android.widget.TextView(context)
            var refreshes = 0
            onUnknownComposerOutcome(store, id, status) { refreshes++ }
            assertTrue(status.text.contains("outcome unknown"))
            assertEquals(0, refreshes)
            assertFalse(composerCanRetry(reopened, request))
            assertFalse(reopened.save(request.copy(body = "edit after claim")))
            assertTrue(reopened.submit(request, dispatcher) is DispatchOutcome.Rejected)
            assertEquals(0, handoffs)
        } finally {
            runCatching { store.finish(request, DispatchOutcome.Rejected("test cleanup")) }
            store.clear(id)
        }
    }

    @Test fun verifiedPretransportRejectionStillAllowsRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val request = OutgoingMessage(id, "+12025550123", "draft", emptyList(), 1)
        val store = ComposerStore(context)
        store.clear(id)
        try {
            val rejected = store.submit(request, MessageDispatcher({ true }, { false }) {
                fail("permission rejection cannot reach carrier")
                DispatchOutcome.Accepted()
            })
            assertTrue(rejected is DispatchOutcome.Rejected)
            val reopened = ComposerStore(context)
            assertFalse(reopened.wasSubmitted(request))
            assertFalse(reopened.isUnknown(id))
            assertTrue(composerCanRetry(reopened, request))
            assertEquals(request, reopened.load(id))
        } finally { store.clear(id) }
    }
}
