package org.fossify.messages.inkpilot.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioCalendarStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "studio-calendar-test-${UUID.randomUUID()}.db"
    private lateinit var repository: StudioRepository
    private lateinit var store: StudioCalendarStore
    private var first = 0L
    private var second = 0L
    private val start = 1_800_000_000_000L

    @Before fun setUp() {
        repository = StudioRepository(context, databaseName); store = StudioCalendarStore(repository)
        first = store.addStaff("Shawn"); second = store.addStaff("Leni")
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(databaseName) }
    private fun proposal(staff: Long = first, room: String = "Studio", time: Long = start) =
        CalendarProposal("Consultation", "appointment", staff, time, time + 3_600_000, "America/Chicago", room)

    @Test fun staffAndSharedRoomConflictsAreRejectedButAdjacentTimesWork() {
        assertTrue(store.schedule(proposal()) is ScheduleResult.Saved)
        val staffConflict = store.schedule(proposal(room = "Other room")) as ScheduleResult.Conflict
        assertFalse(staffConflict.sharedRoom)
        val roomConflict = store.schedule(proposal(staff = second, room = "studio")) as ScheduleResult.Conflict
        assertTrue(roomConflict.sharedRoom)
        assertTrue(store.schedule(proposal(time = start + 3_600_000)) is ScheduleResult.Saved)
        assertEquals(2, store.entries(start, start + 7_200_000).size)
    }

    @Test fun roomlessTasksForDifferentStaffCanOverlap() {
        assertTrue(store.schedule(proposal(room = "").copy(kind = "task")) is ScheduleResult.Saved)
        assertTrue(store.schedule(proposal(staff = second, room = "").copy(kind = "task")) is ScheduleResult.Saved)
    }

    @Test fun cancellingFreesTimeAndPreservesTheHistoricalEntryAcrossReopen() {
        val original = store.schedule(proposal()) as ScheduleResult.Saved
        store.setStatus(original.id, "cancelled")
        assertTrue(store.schedule(proposal()) is ScheduleResult.Saved)
        repository.close()
        repository = StudioRepository(context, databaseName); store = StudioCalendarStore(repository)
        val rows = store.entries(start, start + 3_600_000)
        assertEquals(setOf("cancelled", "scheduled"), rows.map { it.status }.toSet())
        assertEquals(setOf("America/Chicago"), rows.map { it.zoneId }.toSet())
    }

    @Test fun concurrentSchedulingAcrossConnectionsCannotDoubleBook() {
        StudioRepository(context, databaseName).use { otherRepository ->
            val other = StudioCalendarStore(otherRepository)
            val pool = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(1)
            try {
                val futures = listOf(store, other).map { candidate -> pool.submit<ScheduleResult> {
                    ready.await(); candidate.schedule(proposal())
                } }
                ready.countDown()
                val results = futures.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is ScheduleResult.Saved })
                assertEquals(1, results.count { it is ScheduleResult.Conflict })
                assertEquals(1, store.entries(start, start + 3_600_000).size)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun invalidTimeAndMissingStaffDoNotCreateEntries() {
        assertThrows(IllegalArgumentException::class.java) { store.schedule(proposal().copy(endsAt = start)) }
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { store.schedule(proposal(staff = 99999)) }
        assertTrue(store.entries(start, start + 3_600_000).isEmpty())
    }
}
