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
        val events = mutableListOf<String>()
        override fun held() = role
        override fun permitted() = permission
        override fun request() { events += "request" }
        override fun restore() { events += "restore" }
        override fun requestPermission() { events += "permission" }
        override fun place(number: String) { events += "place:$number" }
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
            var panel = PhonePanel(activity, fake, { history++ }, { contacts++ }).view("5550100")
            activity.setContentView(panel)
            button(panel, "Choose inkPilot as Phone app")!!.performClick()
            button(panel, "Call")!!.performClick()
            assertEquals(listOf("request", "unavailable"), fake.events)
            button(panel, "Call history")!!.performClick()
            button(panel, "Contacts")!!.performClick()
            assertEquals(1, history)
            assertEquals(1, contacts)
            fake.role = true
            panel = PhonePanel(activity, fake).view("911") // Mock only, fake Telecom boundary.
            activity.setContentView(panel)
            assertFalse(fake.events.any { it.startsWith("place") }) // Filling never calls.
            fake.permission = false
            button(panel, "Call")!!.performClick()
            assertEquals("permission", fake.events.last())
            fake.permission = true
            button(panel, "Call")!!.performClick()
            assertEquals("place:911", fake.events.last())
            button(panel, "Restore previous Phone app")!!.performClick()
            assertEquals("restore", fake.events.last())
            fake.role = false // Revoked while visible: no external dialer is launched.
            button(panel, "Call")!!.performClick()
            assertEquals("unavailable", fake.events.last())
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
