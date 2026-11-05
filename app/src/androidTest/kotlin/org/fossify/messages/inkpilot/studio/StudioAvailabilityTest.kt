package org.fossify.messages.inkpilot.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioAvailabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "studio-availability-${UUID.randomUUID()}.db"
    private lateinit var repository: StudioRepository
    private lateinit var store: StudioCalendarStore
    private var a = 0L
    private var b = 0L
    private val zone = "America/Chicago"
    private fun at(local: String) = LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
    private fun booking(staff: Long, start: String, room: String = ""): CalendarProposal {
        val epoch = at(start)
        return CalendarProposal("Consult", "appointment", staff, epoch, epoch + 3_600_000, zone, room)
    }

    @Before fun setUp() {
        repository = StudioRepository(context, name)
        store = StudioCalendarStore(repository) { _, _, _ -> }
        a = store.addStaff("A"); b = store.addStaff("B")
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun optionalRoomDoesNotInventConflictsAndSharedRoomDoes() {
        assertTrue(store.schedule(booking(a, "2027-06-01T10:00")) is ScheduleResult.Saved)
        assertTrue(store.schedule(booking(b, "2027-06-01T10:00")) is ScheduleResult.Saved)
        assertTrue(store.schedule(booking(a, "2027-06-01T12:00", "Front")) is ScheduleResult.Saved)
        assertTrue(store.schedule(booking(b, "2027-06-01T12:00", "front")) is ScheduleResult.Conflict)
    }

    @Test fun scopedAvailabilityNeverExposesAnotherClientTitle() {
        val entry = booking(a, "2027-06-01T10:00").copy(title = "Private client consultation")
        assertTrue(store.schedule(entry) is ScheduleResult.Saved)
        val check = CalendarAvailability(store).propose(null, a, "", entry.startsAt, entry.endsAt, zone) as CalendarAvailability.Conflict
        assertEquals("Staff unavailable.", check.reason)
        assertTrue(store.availability(null, 9999, "", entry.startsAt, entry.endsAt, zone) is CalendarAvailability.Conflict)
        assertTrue(store.availability("missing-client", b, "", entry.startsAt, entry.endsAt, zone) is CalendarAvailability.Conflict)
    }

    @Test fun canonicalStudioZoneAndWallHoursHoldAcrossDstTransitions() {
        store.setStudioZone(zone)
        store.setWeeklyHours(a, 7, 9 * 60, 17 * 60) // Sunday
        assertTrue(store.schedule(booking(a, "2027-03-14T09:30")) is ScheduleResult.Saved)
        assertTrue(store.schedule(booking(a, "2027-03-14T08:30")) is ScheduleResult.Unavailable)
        assertTrue(store.schedule(booking(a, "2027-11-07T09:30")) is ScheduleResult.Saved)
        assertTrue(store.schedule(booking(a, "2027-11-07T08:30")) is ScheduleResult.Unavailable)
        repository.close()
        repository = StudioRepository(context, name); store = StudioCalendarStore(repository) { _, _, _ -> }
        assertEquals(zone, store.studioZone().id)
    }

    @Test fun callerDisplayZoneCannotBypassStaffHours() {
        store.setStudioZone(zone)
        store.setWeeklyHours(a, 2, 9 * 60, 17 * 60)
        val instant = at("2027-06-01T03:00")
        val chicago = CalendarProposal("Night", "task", a, instant, instant + 3_600_000, zone, "")
        val london = chicago.copy(zoneId = "Europe/London")
        assertTrue(store.schedule(chicago) is ScheduleResult.Unavailable)
        assertTrue(store.schedule(london) is ScheduleResult.Unavailable)
        assertEquals(
            CalendarAvailability(store).propose(null, a, "", instant, instant + 3_600_000, zone),
            CalendarAvailability(store).propose(null, a, "", instant, instant + 3_600_000, "Europe/London"),
        )
    }

    @Test fun hoursTimeOffAndBoundariesAreCheckedBeforeWriting() {
        store.setStudioZone(zone)
        store.setWeeklyHours(a, 2, 9 * 60, 17 * 60) // Tuesday, 2027-06-01.
        assertTrue(store.schedule(booking(a, "2027-06-01T08:00")) is ScheduleResult.Unavailable)
        assertTrue(store.schedule(booking(a, "2027-06-01T09:00")) is ScheduleResult.Saved)
        store.addTimeOff(a, at("2027-06-01T12:00"), at("2027-06-01T13:00"), "Private medical leave")
        val availability = CalendarAvailability(store).propose(null, a, "",
            at("2027-06-01T12:00"), at("2027-06-01T13:00"), zone) as CalendarAvailability.Conflict
        assertEquals("Staff unavailable.", availability.reason)
        assertTrue(store.schedule(booking(a, "2027-06-01T12:00")) is ScheduleResult.Unavailable)
        assertTrue(store.schedule(booking(a, "2027-06-01T13:00")) is ScheduleResult.Saved)
    }

    @Test fun availabilityChangesCannotInvalidateExistingBookings() {
        store.setStudioZone(zone)
        val entry = booking(a, "2027-06-01T10:00")
        assertTrue(store.schedule(entry) is ScheduleResult.Saved)
        assertThrows(IllegalStateException::class.java) {
            store.setWeeklyHours(a, 2, 12 * 60, 17 * 60)
        }
        assertTrue(store.hours(a).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            store.addTimeOff(a, entry.startsAt, entry.endsAt, "Vacation")
        }
        assertTrue(store.timeOff(a).isEmpty())
    }

    @Test fun dstAmbiguityAndSubminuteDurationsAreRejected() {
        val overlap = booking(a, "2027-11-07T01:30")
        assertThrows(IllegalArgumentException::class.java) { store.schedule(overlap) }
        val gap = LocalDateTime.parse("2027-03-14T02:30")
        assertEquals(0, ZoneId.of(zone).rules.getValidOffsets(gap).size)
        val normal = booking(a, "2027-06-01T10:00")
        assertThrows(IllegalArgumentException::class.java) { store.schedule(normal.copy(endsAt = normal.startsAt + 30_000)) }
        assertThrows(IllegalArgumentException::class.java) { store.schedule(normal.copy(startsAt = normal.startsAt + 1)) }
    }

    @Test fun editAndReminderVersionSurviveReopenAndOldVersionCannotDeliver() {
        val first = booking(a, "2027-06-01T10:00").copy(reminderAt = at("2027-06-01T09:30"))
        val saved = store.schedule(first) as ScheduleResult.Saved
        val changed = booking(a, "2027-06-01T11:00").copy(reminderAt = at("2027-06-01T10:30"))
        assertTrue(store.edit(saved.id, changed) is ScheduleResult.Saved)
        repository.close()
        repository = StudioRepository(context, name); store = StudioCalendarStore(repository) { _, _, _ -> }
        assertNull(store.dueReminder(saved.id, 0, at("2027-06-01T11:00")))
        val row = store.entries(at("2027-06-01T11:00"), at("2027-06-01T12:00")).single()
        assertEquals(1L, row.revision)
        assertEquals(changed.reminderAt, row.reminderAt)
        assertEquals(saved.id, store.dueReminder(saved.id, 1, at("2027-06-01T11:00"))?.id)
        assertTrue(store.markReminderDelivered(saved.id, 1))
        assertNull(store.dueReminder(saved.id, 1, at("2027-06-01T11:00")))
        store.setStatus(saved.id, "cancelled")
        assertNull(store.dueReminder(saved.id, 1, at("2027-06-01T11:00")))
    }

    @Test fun reminderWorkIsVersionedUniqueAndQueuedDurably() {
        val scheduler = CalendarReminderScheduler(context)
        val id = 8_000_000L + System.nanoTime() % 1_000_000
        val name = CalendarReminderScheduler.name(id, 3)
        val manager = WorkManager.getInstance(context)
        try {
            scheduler.schedule(id, 3, System.currentTimeMillis() + 86_400_000)!!.result.get(15, TimeUnit.SECONDS)
            scheduler.schedule(id, 3, System.currentTimeMillis() + 86_400_000)!!.result.get(15, TimeUnit.SECONDS)
            val work = manager.getWorkInfosForUniqueWork(name).get(15, TimeUnit.SECONDS)
            assertEquals(1, work.size)
            assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
        } finally {
            manager.cancelUniqueWork(name).result.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun frozenV4MigrationPreservesCalendarAndImageLinks() {
        repository.close(); context.deleteDatabase(name)
        // Build the complete released v4 shape, including draft/send tables. A partial
        // hand-written fixture once failed onOpen before calendar migration was exercised.
        org.fossify.messages.inkpilot.data.StudioV1Fixture(context, name).use { fixture ->
            val old = fixture.writableDatabase
            org.fossify.messages.inkpilot.data.StudioRevisionSchema.upgrade(old)
            old.execSQL("INSERT INTO studio_contacts(id,number,display_name) VALUES('client','+12025550101','Synthetic')")
            org.fossify.messages.inkpilot.data.StudioIdentitySchema.create(old)
            StudioImageLinkSchema.create(old)
            old.execSQL("INSERT INTO studio_staff VALUES(71,'A')")
            old.execSQL("INSERT INTO studio_calendar VALUES(72,'Original','task',71,1800000000000,1800003600000,'America/Chicago','','scheduled')")
            old.execSQL("INSERT INTO studio_images VALUES(73,'old.png','Old',1)")
            old.execSQL("INSERT INTO image_links VALUES(73,'client','project',71,72,'MMS',74,75)")
            old.execSQL("INSERT INTO inbound_events VALUES('v4-event','75','client','+12025550101','Synthetic',1,1,0)")
            old.execSQL("INSERT INTO held_drafts VALUES('v4-held','v4-event','CUSTOMER_SERVICE_REPLY','75','client','+12025550101',0,'Held text','HELD',2)")
            old.version = 4
        }
        repository = StudioRepository(context, name); store = StudioCalendarStore(repository) { _, _, _ -> }
        assertEquals(7, repository.database.version)
        assertEquals(ZoneId.systemDefault(), store.studioZone())
        val row = store.entries(1_800_000_000_000, 1_800_003_600_000).single()
        assertEquals("Original", row.title)
        assertEquals(72L, row.id)
        assertEquals(0L, row.revision)
        repository.database.rawQuery("SELECT body,state FROM held_drafts WHERE draft_id='v4-held'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("Held text", it.getString(0))
            assertEquals("HELD", it.getString(1))
        }
        repository.database.rawQuery("SELECT image_id,contact_id,appointment_id,source_id FROM image_links", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(73L, it.getLong(0)); assertEquals("client", it.getString(1))
            assertEquals(72L, it.getLong(2)); assertEquals(74L, it.getLong(3))
        }
    }
}
