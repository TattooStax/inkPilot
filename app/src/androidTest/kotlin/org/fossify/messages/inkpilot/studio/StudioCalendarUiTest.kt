package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executor
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioCalendarUiTest {
    @Test fun renderedActionListsCanEditAndCompleteAndOpenHours() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-calendar-ui-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        try {
            val store = StudioCalendarStore(repository) { _, _, _ -> }
            val staffId = store.addStaff("Synthetic artist")
            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone)
            val start = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val saved = store.schedule(CalendarProposal(
                "Synthetic appointment", "appointment", staffId, start, start + 3_600_000, zone.id, "",
            )) as ScheduleResult.Saved
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                lateinit var panel: StudioCalendarPanel
                scenario.onActivity { activity ->
                    panel = StudioCalendarPanel(
                        StudioPanelHost(activity, Executor { it.run() }) { fail(it) }, store,
                    )
                    activity.setContentView(panel.view())
                    val entry = store.entries(start, start + 3_600_000).single { it.id == saved.id }
                    val details = panel.detailsDialog(entry)
                    details.show()
                    assertEquals(3, details.listView.count)
                    assertTrue(details.listView.isShown)
                    details.listView.performItemClick(details.listView.getChildAt(1), 1,
                        details.listView.getItemIdAtPosition(1))
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    assertEquals("completed", store.entries(start, start + 3_600_000)
                        .single { it.id == saved.id }.status)
                    val availability = panel.availabilityDialog(StudioStaff(staffId, "Synthetic artist"),
                        emptyList(), emptyList())
                    availability.show()
                    assertEquals(2, availability.listView.count)
                    assertTrue(availability.listView.isShown)
                    availability.listView.performItemClick(availability.listView.getChildAt(0), 0,
                        availability.listView.getItemIdAtPosition(0))
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val active = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                assertTrue(active?.findAccessibilityNodeInfosByText("weekly hours")?.isNotEmpty() == true)
            }
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
