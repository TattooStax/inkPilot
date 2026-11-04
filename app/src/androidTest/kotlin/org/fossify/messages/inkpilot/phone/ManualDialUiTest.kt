package org.fossify.messages.inkpilot.phone

import android.content.ActivityNotFoundException
import android.content.Intent
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
        var target: String? = "mock.dialer"
        val events = mutableListOf<String>()
        override fun held() = role
        override fun permitted() = permission
        override fun fallbackPackage() = target
        override fun request() { events += "request" } // Simulated refusal: role stays false.
        override fun restore() { events += "restore" }
        override fun requestPermission() { events += "permission" }
        override fun openFallback(target: String, number: String) { events += "fallback:$target:$number" }
        override fun place(number: String) { events += "place:$number" }
        override fun unavailable() { events += "unavailable" }
    }
    private fun button(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), label)?.let { return it }
        return null
    }

    @Test fun refusalRevocationPermissionDenialAndEmergencyMockUseCorrectDispatch() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val fake = FakeDial()
            var panel = PhonePanel(activity, fake).view("5550100")
            activity.setContentView(panel)
            button(panel, "Choose inkPilot as Phone app")!!.performClick()
            button(panel, "Call")!!.performClick()
            assertEquals(listOf("request", "fallback:mock.dialer:5550100"), fake.events)
            fake.role = true
            panel = PhonePanel(activity, fake).view("911") // Mock only, fake Telecom boundary.
            activity.setContentView(panel)
            assertFalse(fake.events.any { it.startsWith("place") }) // Filling number never calls.
            fake.permission = false
            button(panel, "Call")!!.performClick()
            assertEquals("permission", fake.events.last())
            fake.permission = true
            button(panel, "Call")!!.performClick()
            assertEquals("place:911", fake.events.last())
            button(panel, "Restore previous Phone app")!!.performClick()
            assertEquals("restore", fake.events.last())
            fake.role = false // Role revoked while panel is still visible.
            button(panel, "Call")!!.performClick()
            assertEquals("fallback:mock.dialer:911", fake.events.last())
            fake.target = null
            button(panel, "Call")!!.performClick()
            assertEquals("unavailable", fake.events.last())
            fake.target = activity.packageName // Never recurse into ourselves.
            button(panel, "Call")!!.performClick()
            assertEquals("unavailable", fake.events.last())
            val before = fake.events.size
            button(PhonePanel(activity, fake).view(""), "Call")!!.performClick()
            assertEquals(before, fake.events.size)
        } }
    }

    @Test fun missingExternalHandlerReportsFailureAndFallbackIntentNeverPlacesCall() {
        ActivityScenario.launch(ManualCallActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val errors = mutableListOf<String>()
            var observed: Intent? = null
            val actions = AndroidDialActions(activity, launch = {
                observed = it
                throw ActivityNotFoundException("mock missing dialer")
            }, report = errors::add)
            actions.openFallback("mock.dialer", "5550100")
            assertEquals(Intent.ACTION_DIAL, observed!!.action)
            assertEquals("mock.dialer", observed!!.getPackage())
            assertEquals("tel:5550100", observed!!.data.toString())
            assertEquals(listOf("No phone dialer is available."), errors)
        } }
    }
}
