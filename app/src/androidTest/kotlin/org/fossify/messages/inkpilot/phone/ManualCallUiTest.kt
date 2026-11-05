package org.fossify.messages.inkpilot.phone

import android.telecom.Call
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualCallUiTest {
    private class FakeCalls : ManualCallController {
        var state = listOf(ManualCallState("one", "5550100", Call.STATE_RINGING))
        val dispatched = mutableListOf<Triple<String, String, String>>()
        override fun calls() = state
        override fun muted() = false
        override fun routes() = listOf("Speaker" to 8)
        override fun dispatch(id: String, action: String, argument: String) { dispatched += Triple(id, action, argument) }
    }
    private fun row(view: View, key: String): TextView = view.findViewWithTag(key)
        ?: error("Missing control $key")
    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    @Test fun incomingOngoingMultipleCallControlsAndPrivacyTransitions() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val fake = FakeCalls()
            val screen = ManualCallScreen(activity, {}, {})
            activity.setContentView(screen.view)
            screen.update(fake, true)
            assertFalse(texts(screen.view).any { "5550100" in it })
            row(screen.view, "one:answer").performClick()
            row(screen.view, "one:decline").performClick()
            assertEquals(listOf("answer", "decline"), fake.dispatched.map { it.second })
            fake.state = listOf(ManualCallState("one", "5550100", Call.STATE_ACTIVE, hold = true, merge = true, swap = true, join = true, postDial = true),
                ManualCallState("two", "5550101", Call.STATE_SELECT_PHONE_ACCOUNT, accounts = listOf("mock-sim")))
            screen.update(fake, false)
            assertTrue(texts(screen.view).contains("5550100"))
            listOf("hold", "merge", "swap", "join", "continue", "cancel", "digit5", "end").forEach { row(screen.view, "one:" + it).performClick() }
            row(screen.view, "two:simmock-sim").performClick()
            row(screen.view, "mute").performClick()
            row(screen.view, "route:8").performClick()
            assertEquals(listOf("answer", "decline", "hold", "merge", "swap", "join", "continue", "cancel",
                "digit", "end", "sim", "mute", "route"), fake.dispatched.map { it.second })
            assertTrue(fake.dispatched.contains(Triple("one", "digit", "5")))
            assertTrue(fake.dispatched.contains(Triple("two", "sim", "mock-sim")))
            assertTrue(fake.dispatched.contains(Triple("", "route", "8")))
            fake.state = listOf(fake.state.first().copy(state = Call.STATE_HOLDING))
            screen.update(fake, true)
            assertFalse(texts(screen.view).any { "5550100" in it })
            assertNull(screen.view.findViewWithTag<View>("one:digit5"))
            assertEquals("Resume", row(screen.view, "one:hold").text.toString())
            fake.state = listOf(ManualCallState("emergency-mock", "911", Call.STATE_RINGING))
            screen.update(fake, true)
            assertFalse(texts(screen.view).any { "911" in it })
            row(screen.view, "emergency-mock:answer").performClick()
            assertEquals(Triple("emergency-mock", "answer", ""), fake.dispatched.last())
            screen.update(null, true)
            assertTrue(texts(screen.view).contains("Call ended"))
        } }
    }

    @Test fun lockedSimAccountChoicesHidePhoneAccountIdsWithoutChangingSelection() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val fake = FakeCalls().apply {
                state = listOf(ManualCallState("sim", null, Call.STATE_SELECT_PHONE_ACCOUNT,
                    accounts = listOf("line-5550101", "line-5550102")))
            }
            val screen = ManualCallScreen(activity, {}, {})
            activity.setContentView(screen.view)
            screen.update(fake, true)
            assertFalse(texts(screen.view).any { "5550101" in it || "5550102" in it })
            assertEquals("Use SIM 1", row(screen.view, "sim:simline-5550101").text.toString())
            assertEquals("Use SIM 2", row(screen.view, "sim:simline-5550102").text.toString())
            row(screen.view, "sim:simline-5550102").performClick()
            assertEquals(Triple("sim", "sim", "line-5550102"), fake.dispatched.last())
            screen.update(fake, false)
            assertEquals("Use SIM line-5550102", row(screen.view, "sim:simline-5550102").text.toString())
        } }
    }

    @Test fun repeatedUpdatesRetainSmallDisplayScrollAndAccessibilityFocusTargets() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val fake = FakeCalls().apply {
                state = (1..3).map { ManualCallState(it.toString(), "555010$it", Call.STATE_ACTIVE, hold = true) }
            }
            val screen = ManualCallScreen(activity, {}, {})
            activity.setContentView(screen.view)
            screen.update(fake, false)
            screen.view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            screen.view.layout(0, 0, 320, 480)
            val speaker = row(screen.view, "route:8")
            speaker.isFocusableInTouchMode = true
            assertTrue(speaker.requestFocus())
            screen.view.scrollTo(0, 500)
            val before = screen.view.scrollY
            assertTrue(before > 0)
            repeat(5) { screen.update(fake, false) }
            assertSame(speaker, row(screen.view, "route:8"))
            assertTrue(speaker.hasFocus())
            assertEquals(before, screen.view.scrollY)
            speaker.performClick()
            assertEquals(Triple("", "route", "8"), fake.dispatched.last())
        } }
    }

    @Test fun inheritedUnlockCannotClearSecureWindowForEitherPasswordConfiguration() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            listOf(false, true).forEach { passwordOn ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                withSecureCallWindow(activity) {
                    // Mirrors inherited StudioAppLock behavior for each configuration.
                    if (!passwordOn) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            runCatching { withSecureCallWindow(activity) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                error("mock unlock cancellation")
            } }
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        } }
    }
}
