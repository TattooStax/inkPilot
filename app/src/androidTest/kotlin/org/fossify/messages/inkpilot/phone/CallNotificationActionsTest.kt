package org.fossify.messages.inkpilot.phone

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** No role, Telecom service, or carrier call is used by these action tests. */
@RunWith(AndroidJUnit4::class)
class CallNotificationActionsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private class FakeCall : IncomingCallTarget {
        var ringing = true
        var answers = 0
        var declines = 0
        override fun ringing() = ringing
        override fun answer() { answers++ }
        override fun decline() { declines++ }
    }

    @Test fun onlyTheExactLiveRingingTokenCanAnswerOnce() {
        val actions = IncomingCallActionRegistry { "first-call" }
        val call = FakeCall()
        val token = actions.register(call)
        assertFalse(actions.perform("foreign-call", IncomingCallDecision.ANSWER))
        assertEquals(0, call.answers)
        assertTrue(actions.perform(token, IncomingCallDecision.ANSWER))
        assertFalse(actions.perform(token, IncomingCallDecision.DECLINE))
        assertEquals(1, call.answers)
        assertEquals(0, call.declines)
    }

    @Test fun removedOrNoLongerRingingCallRejectsOldActions() {
        val actions = IncomingCallActionRegistry { "removed-call" }
        val removed = FakeCall()
        val token = actions.register(removed)
        actions.revoke(token)
        assertFalse(actions.perform(token, IncomingCallDecision.DECLINE))
        assertFalse(actions.perform("missing-call", IncomingCallDecision.ANSWER))
        assertEquals(0, removed.declines)
        val later = FakeCall()
        val laterToken = actions.register(later)
        assertNotEquals(token, laterToken)
        assertFalse(actions.perform(token, IncomingCallDecision.ANSWER))
        later.ringing = false
        assertFalse(actions.perform(laterToken, IncomingCallDecision.ANSWER))
        assertFalse(actions.live(laterToken))
        assertEquals(0, later.answers)
    }

    @Test fun failedTelecomActionCannotReplayToken() {
        val actions = IncomingCallActionRegistry { "failing-call" }
        var attempts = 0
        val token = actions.register(object : IncomingCallTarget {
            override fun ringing() = true
            override fun answer() { attempts++; error("Telecom refused") }
            override fun decline() { attempts++ }
        })
        assertTrue(runCatching { actions.perform(token, IncomingCallDecision.ANSWER) }.isFailure)
        assertFalse(actions.live(token))
        assertFalse(actions.perform(token, IncomingCallDecision.ANSWER))
        assertEquals(1, attempts)
    }

    @Test fun malformedAndActionMismatchedUrisCannotRoute() {
        val answer = IncomingCallActionIntent.intent(context, "call-one", IncomingCallDecision.ANSWER)
        val decline = IncomingCallActionIntent.intent(context, "call-one", IncomingCallDecision.DECLINE)
        assertEquals("call-one", IncomingCallActionIntent.parse(answer)?.token)
        assertEquals(IncomingCallDecision.ANSWER, IncomingCallActionIntent.parse(answer)?.decision)
        assertFalse(answer.filterEquals(decline))
        assertFalse(answer.filterEquals(
            IncomingCallActionIntent.intent(context, "call-two", IncomingCallDecision.ANSWER)))
        assertNull(IncomingCallActionIntent.parse(Intent(context, CallNotificationActionReceiver::class.java)))
        assertNull(IncomingCallActionIntent.parse(Intent(answer).setAction("foreign.action")))
        assertNull(IncomingCallActionIntent.parse(Intent(answer).setData(decline.data)))
        assertNull(IncomingCallActionIntent.parse(Intent(answer).setData(Uri.parse("tel:call-one"))))
    }

    @Test fun oldPendingActionIsDistinctAndCanBeCanceledWithoutTouchingTheNextCall() {
        val old = IncomingCallActionIntent.pending(context, "old-call", IncomingCallDecision.ANSWER)
        val next = IncomingCallActionIntent.pending(context, "next-call", IncomingCallDecision.ANSWER)
        try {
            assertNotEquals(old, next)
            IncomingCallActionIntent.cancel(context, "old-call")
            try { old.send(); fail("Canceled old call action was still usable") }
            catch (_: PendingIntent.CanceledException) { /* Expected: never routes to the next call. */ }
        } finally {
            IncomingCallActionIntent.cancel(context, "next-call")
        }
    }

    @Test fun genericIncomingNotificationHasActionsButConsumedTokenHasNoFullScreenIntent() {
        val content = PendingIntent.getActivity(context, 725,
            Intent(context, ManualCallActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val token = "notification-test"
        try {
            val live = CallNotificationPresentation.build(context, content, true, token)
            assertEquals(Notification.VISIBILITY_PUBLIC, live.visibility)
            assertEquals(2, live.actions?.size)
            assertTrue(live.fullScreenIntent != null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(live.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("CallStyle") == true)
            }
            val consumed = CallNotificationPresentation.build(context, content, true, null, true)
            assertNull(consumed.fullScreenIntent)
            assertTrue(consumed.actions.isNullOrEmpty())
            assertEquals(Notification.VISIBILITY_PUBLIC, consumed.visibility)
            assertEquals("Incoming phone call", consumed.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("Call action failed. Tap for manual controls",
                consumed.extras.getCharSequence(Notification.EXTRA_TEXT))
            val ongoing = CallNotificationPresentation.build(context, content, false, null)
            assertNull(ongoing.fullScreenIntent)
            assertEquals(Notification.VISIBILITY_PRIVATE, ongoing.visibility)
        } finally {
            IncomingCallActionIntent.cancel(context, token)
            content.cancel()
        }
    }

    @Test fun actionReceiverIsNotExported() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, CallNotificationActionReceiver::class.java), 0)
        assertFalse(info.exported)
    }
}
