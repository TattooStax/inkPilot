package org.fossify.messages.inkpilot.smoke

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Button
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.StudioTab
import org.fossify.messages.inkpilot.ui.prepareInShellComposerSend
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.confirmMmsSent
import org.fossify.messages.messaging.recordMmsFailure
import org.fossify.messages.messaging.isMmsReportedFailed
import org.fossify.messages.messaging.hasCompletedCompose
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.MessageDispatcher
import org.fossify.messages.messaging.OutgoingMessage
import org.fossify.messages.messaging.submit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic callback state only. No radio or native provider mutation. */
@RunWith(AndroidJUnit4::class)
class MmsComposerOutcomeTest {
    @Test fun revokedSimAccessBeforeClaimRetainsEditableDraft() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE)
        val request = OutgoingMessage(id, "+15550106669", "human draft", emptyList(), 3)
        val store = ComposerStore(context)
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val denied = prepareInShellComposerSend(activity, store, request) { null }.getOrThrow()
                    assertTrue(denied is DispatchOutcome.Rejected)
                    val revoked = prepareInShellComposerSend(activity, store, request) {
                        throw SecurityException("SIM access revoked before claim")
                    }.getOrThrow()
                    assertTrue(revoked is DispatchOutcome.Rejected)
                    assertTrue(store.save(request.copy(body = "still editable")))
                    assertFalse(store.isUnknown(id))
                }
            }
        } finally { store.clear(id) }
    }

    @Test fun acceptsOnlyCanonicalNativeMmsCallbackRows() {
        assertTrue(org.fossify.messages.receivers.isMmsCallbackRow(Uri.parse("content://mms/123")))
        assertTrue(org.fossify.messages.receivers.isMmsCallbackRow(Uri.parse("content://mms/outbox/123")))
        assertFalse(org.fossify.messages.receivers.isMmsCallbackRow(Uri.parse("content://mms/part/123")))
        assertFalse(org.fossify.messages.receivers.isMmsCallbackRow(Uri.parse("content://sms/outbox/123")))
        assertFalse(org.fossify.messages.receivers.isMmsCallbackRow(Uri.parse("content://mms/outbox/0")))
        val row = Uri.parse("content://mms/outbox/123")
        assertTrue(org.fossify.messages.receivers.mmsCallbackRowBound(row, row))
        assertFalse(org.fossify.messages.receivers.mmsCallbackRowBound(row, Uri.parse("content://mms/outbox/124")))
        assertFalse(org.fossify.messages.receivers.mmsCallbackRowBound(Uri.parse("content://sms/123"), row))
    }
    @Test fun onlyMatchingConfirmedAttemptCanUnlockAnUnknownMms() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE)
        val store = ComposerStore(context)
        val request = OutgoingMessage(id, "+15550108888", "MMS caption", emptyList(), 3)
        try {
            assertTrue(store.submit(request, MessageDispatcher({ true }, { true }) {
                DispatchOutcome.Unknown("carrier pending")
            }) is DispatchOutcome.Unknown)
            assertFalse(store.confirmMmsSent(id, "wrong-attempt"))
            assertFalse(store.confirmMmsSent(id + 1, request.attemptId))
            assertTrue(store.isUnknown(id))
            assertFalse(ComposerStore(context).save(request.copy(body = "edit after handoff")))
            assertEquals(request, ComposerStore(context).load(id))
            assertTrue(store.recordMmsFailure(id, request.attemptId))
            assertTrue(ComposerStore(context).isMmsReportedFailed(id))
            assertTrue(store.isUnknown(id))
            assertTrue(store.confirmMmsSent(id, request.attemptId))
            assertTrue(ComposerStore(context).hasCompletedCompose(id))
            assertNull(ComposerStore(context).load(id))
            assertEquals("", ComposerStore(context).getOrCreate(request).body)
            assertFalse(ComposerStore(context).save(request.copy(attemptId = UUID.randomUUID().toString())))
            assertFalse(store.confirmMmsSent(id, request.attemptId))
            assertTrue(store.submit(request, MessageDispatcher({ true }, { true }) {
                throw AssertionError("confirmed attempt must not replay")
            }) is DispatchOutcome.Rejected)
        } finally { store.clear(id) }
    }

    @Test fun pendingComposerRendersReadOnlyUntilExactCallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val route = StudioRoute.Compose(listOf("+15550106666"), "draft", emptyList())
        val request = OutgoingMessage(route.composerId, "+15550106666", "draft", emptyList(), 3)
        val store = ComposerStore(context)
        try {
            assertTrue(store.submit(request, MessageDispatcher({ true }, { true }) {
                DispatchOutcome.Unknown("pending")
            }) is DispatchOutcome.Unknown)
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openRoute(route)
                    val views = descendants(activity.window.decorView)
                    assertTrue(views.filterIsInstance<EditText>().none { it.isEnabled })
                    assertFalse(views.filterIsInstance<Button>()
                        .first { it.text.toString().startsWith("Attach image") }.isEnabled)
                    assertFalse(views.filterIsInstance<Button>()
                        .first { it.text == "Send message" }.isEnabled)
                }
                assertTrue(store.confirmMmsSent(route.composerId, request.attemptId))
                scenario.onActivity { activity ->
                    activity.composerRoutes.refreshFromProvider()
                    val title = activity.findViewById<android.widget.TextView>(org.fossify.messages.R.id.inkpilot_title)
                    assertEquals(StudioTab.SMS.label, title.text.toString())
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<EditText>()
                        .none { it.text.toString() == "draft" })
                }
            }
        } finally { store.clear(route.composerId) }
    }

    @Test fun hiddenCompletedComposeCannotReseedSentText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val route = StudioRoute.Compose(listOf("+15550106667"), "sent caption", emptyList())
        val request = OutgoingMessage(route.composerId, "+15550106667", "sent caption", emptyList(), 3)
        val store = ComposerStore(context)
        try {
            store.submit(request, MessageDispatcher({ true }, { true }) { DispatchOutcome.Unknown("pending") })
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openRoute(route)
                    activity.openRoute(StudioRoute.Tab(org.fossify.messages.inkpilot.ui.StudioTab.CONTACTS))
                }
                assertTrue(store.confirmMmsSent(route.composerId, request.attemptId))
                scenario.onActivity { activity ->
                    activity.composerRoutes.refreshFromProvider()
                    activity.openRoute(StudioRoute.Tab(org.fossify.messages.inkpilot.ui.StudioTab.SMS))
                    val title = activity.findViewById<android.widget.TextView>(org.fossify.messages.R.id.inkpilot_title)
                    assertEquals(StudioTab.SMS.label, title.text.toString())
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<EditText>()
                        .none { it.text.toString() == "sent caption" })
                }
            }
        } finally { store.clear(route.composerId) }
    }

    @Test fun callbackWhileObserverStoppedPrunesRouteBeforeResumeRender() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val route = StudioRoute.Compose(listOf("+15550106668"), "already sent", emptyList())
        val request = OutgoingMessage(route.composerId, "+15550106668", "already sent", emptyList(), 3)
        val store = ComposerStore(context)
        try {
            store.submit(request, MessageDispatcher({ true }, { true }) { DispatchOutcome.Unknown("pending") })
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { it.openRoute(route) }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue(store.confirmMmsSent(route.composerId, request.attemptId))
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    val title = activity.findViewById<android.widget.TextView>(org.fossify.messages.R.id.inkpilot_title)
                    assertEquals(StudioTab.SMS.label, title.text.toString())
                    assertFalse(descendants(activity.window.decorView).filterIsInstance<EditText>()
                        .any { it.text.toString() == "already sent" })
                }
            }
        } finally { store.clear(route.composerId) }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    @Test fun confirmedCallbackSurvivesDeathBetweenJournalAndCleanup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -UUID.randomUUID().leastSignificantBits.and(Long.MAX_VALUE)
        val store = ComposerStore(context)
        val request = OutgoingMessage(id, "+15550107777", "pending", emptyList(), 3)
        try {
            store.submit(request, MessageDispatcher({ true }, { true }) { DispatchOutcome.Unknown("pending") })
            assertTrue(store.isUnknown(id))
            assertTrue(context.getSharedPreferences("durable_composers", 0).edit()
                .putString("mms-confirmed:$id", request.attemptId).commit())
            val recovered = ComposerStore(context)
            assertTrue(recovered.hasCompletedCompose(id))
            assertFalse(recovered.pendingFile(id).exists())
            assertNull(recovered.load(id))
            assertEquals(null, context.getSharedPreferences("durable_composers", 0)
                .getString("mms-confirmed:$id", null))
        } finally { store.clear(id) }
    }
}
