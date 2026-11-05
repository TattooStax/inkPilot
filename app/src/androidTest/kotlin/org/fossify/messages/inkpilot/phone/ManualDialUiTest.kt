package org.fossify.messages.inkpilot.phone

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualDialUiTest {
    private class FakeDial : ManualDialActions {
        var role = false
        var permission = true
        var placeResult = ManualDialResult.LOCAL_CONTROLS
        val events = mutableListOf<String>()
        override fun held() = role
        override fun permitted() = permission
        override fun request() { events += "request" }
        override fun restore() { events += "restore" }
        override fun requestPermission() { events += "permission" }
        override fun place(number: String): ManualDialResult { events += "place:$number"; return placeResult }
        override fun unavailable() { events += "unavailable" }
    }
    private fun button(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), label)?.let { return it }
        return null
    }

    @Test fun roleRefusalPermissionDenialAndEmergencyMockStayInPhoneTab() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val fake = FakeDial()
            var history = 0
            var contacts = 0
            val callControls = mutableListOf<Boolean>()
            var panel = PhonePanel(activity, fake, { history++ }, { contacts++ }, { callControls += it }).view("5550100")
            activity.setContentView(panel)
            button(panel, "Choose inkPilot as Phone app")!!.performClick()
            button(panel, "Call")!!.performClick()
            assertEquals(listOf("request", "unavailable"), fake.events)
            assertTrue(callControls.isEmpty())
            button(panel, "Call history")!!.performClick()
            button(panel, "Contacts")!!.performClick()
            assertEquals(1, history)
            assertEquals(1, contacts)
            fake.role = true
            var active = false
            panel = PhonePanel(activity, fake, openCallControls = { callControls += it },
                hasActiveCall = { active }).view("911") // Mock only, fake Telecom boundary.
            activity.setContentView(panel)
            assertFalse(fake.events.any { it.startsWith("place") }) // Filling never calls.
            fake.permission = false
            button(panel, "Call")!!.performClick()
            assertEquals("permission", fake.events.last())
            assertTrue(callControls.isEmpty())
            fake.permission = true
            fake.placeResult = ManualDialResult.SYSTEM_HANDOFF
            button(panel, "Call")!!.performClick()
            assertEquals("place:911", fake.events.last())
            assertTrue(callControls.isEmpty())
            fake.placeResult = ManualDialResult.FAILED
            button(panel, "Call")!!.performClick()
            assertEquals("place:911", fake.events.last())
            assertTrue(callControls.isEmpty())
            button(panel, "Call controls")!!.performClick()
            assertTrue(callControls.isEmpty()) // No call is active; never show an ended call screen.
            active = true
            button(panel, "Call controls")!!.performClick()
            assertEquals(listOf(false), callControls) // Cached canvas still has a live return path.
            val ordinary = PhonePanel(activity, fake, openCallControls = { callControls += it },
                hasActiveCall = { active }).view("5550100")
            activity.setContentView(ordinary)
            fake.placeResult = ManualDialResult.LOCAL_CONTROLS
            button(ordinary, "Call")!!.performClick()
            assertEquals("place:5550100", fake.events.last())
            assertEquals(listOf(false, true), callControls)
            button(panel, "Restore previous Phone app")!!.performClick()
            assertEquals("restore", fake.events.last())
            fake.role = false // Revoked while visible: no external dialer is launched.
            button(panel, "Call")!!.performClick()
            assertEquals("unavailable", fake.events.last())
            assertEquals(listOf(false, true), callControls)
            val before = fake.events.size
            button(PhonePanel(activity, fake).view(""), "Call")!!.performClick()
            assertEquals(before, fake.events.size)
        } }
    }

    @Test fun unavailableRoleReportsNeedForInShellPhoneRole() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val errors = mutableListOf<String>()
            AndroidDialActions(activity, report = errors::add).unavailable()
            assertEquals(listOf("Choose inkPilot as your Phone app before calling."), errors)
        } }
    }
}
