package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioCalendarUiTest {
    @Test fun calendarActionsWaitForStoredZoneWithDelayedExecutor() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-calendar-zone-ui-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        try {
            val store = StudioCalendarStore(repository) { _, _, _ -> }
            val savedZone = if (ZoneId.systemDefault().id == "America/Chicago") "UTC" else "America/Chicago"
            store.setStudioZone(savedZone)
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val pending = ArrayDeque<Runnable>()
                    val panel = StudioCalendarPanel(
                        StudioPanelHost(activity, Executor { pending.addLast(it) }) { fail(it) }, store,
                    )
                    val screen = panel.view()
                    activity.setContentView(screen)
                    val before = labels(screen)
                    assertTrue(before.contains("Loading studio time zone…"))
                    assertTrue(before.contains("Retry calendar"))
                    assertFalse(before.contains("Add appointment or task"))
                    assertFalse(before.contains("Staff hours and time off"))
                    assertFalse(before.any { it.startsWith("Studio time zone ·") })

                    pending.removeFirst().run()
                    val after = labels(screen)
                    assertFalse(after.contains("Loading studio time zone…"))
                    assertTrue(after.contains("Add appointment or task"))
                    assertFalse(after.contains("Staff hours and time off"))
                    assertFalse(after.contains("Add staff member"))
                    assertFalse(after.contains("Studio time zone · $savedZone"))
                    assertFalse(after.contains("Calendar"))
                }
            }
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun calendarSettingsOpenFromDayAndReturnToDayAfterCancel() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-calendar-settings-ui-${UUID.randomUUID()}.db"
        val repository = StudioRepository(context, name)
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val host = StudioPanelHost(activity, Executor { it.run() }) { fail(it) }
                    val panel = StudioCalendarPanel(host, StudioCalendarStore(repository) { _, _, _ -> })
                    activity.setContentView(panel.view())
                }
                SmokeViews.clickDescription("Calendar settings")
                SmokeViews.assertText("Staff hours and time off")
                SmokeViews.assertText("Add staff member")
                SmokeViews.clickText("Add staff member")
                SmokeViews.assertText("Save")
                SmokeViews.clickText("Cancel")
                SmokeViews.clickDescription("Calendar settings")
                SmokeViews.clickText("Staff hours and time off")
                SmokeViews.assertText("Save")
                SmokeViews.clickText("Cancel")
                SmokeViews.clickDescription("Calendar settings")
                SmokeViews.clickText("Studio time zone · ${ZoneId.systemDefault().id}")
                SmokeViews.assertText("Save")
                SmokeViews.clickText("Cancel")
                SmokeViews.assertText("Add appointment or task")
            }
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    private fun labels(root: View): List<String> = buildList {
        fun collect(view: View) {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(root)
    }

    @Test fun blockedReminderStatusIsVisibleAndClearsAfterAccessReturns() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = StudioPanelHost(activity, Executor { it.run() }) { fail(it) }
                val blocked = requireNotNull(calendarReminderNotice(host, false))
                assertTrue(labels(blocked).any { it.startsWith("Reminders paused.") })
                assertNull(calendarReminderNotice(host, true))
            }
        }
    }

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
                SmokeViews.assertText("Synthetic artist · weekly hours")
            }
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
