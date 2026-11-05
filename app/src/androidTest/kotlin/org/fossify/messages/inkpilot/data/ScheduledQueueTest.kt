package org.fossify.messages.inkpilot.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.messaging.*
import org.fossify.messages.models.Message
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local rows only. The injected post never creates an alarm or invokes a radio. */
@RunWith(AndroidJUnit4::class)
class ScheduledQueueTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun message() = Message(System.nanoTime(), "original human text", 6, 0, arrayListOf(), 1,
        false, System.nanoTime(), false, null, "", "", "", -1, true)

    @Test fun alarmPostFailureLeavesExactDueRowAndBodyRecoverable() {
        val row = message()
        try {
            assertEquals(ScheduledQueueResult.POST_FAILED, context.queueScheduled(row) {
                assertEquals(row.body, context.messagesDB.get(row.key())?.body)
                error("synthetic alarm post failure")
            })
            assertEquals(row, context.messagesDB.get(row.key()))
            ScheduledVerdicts(context).use { assertEquals("QUEUED", it.state(row.key())) }
            assertTrue(context.dispatchScheduled(row) is DispatchOutcome.Rejected)
            assertEquals(row, context.messagesDB.get(row.key()))
        } finally { context.messagesDB.delete(row.key()) }
    }
    @Test fun claimedUnknownEditCannotOverwriteOriginalOrPostAlarm() {
        val row = message()
        try {
            context.queueScheduled(row) { }
            ScheduledVerdicts(context).use { assertTrue(it.claim(row.key(), 1)); it.unknown(row.key()) }
            var posted = false
            assertEquals(ScheduledQueueResult.CLAIMED,
                context.queueScheduled(row.copy(body = "edited"), row.key()) { posted = true })
            assertFalse(posted)
            assertEquals(row, context.messagesDB.get(row.key()))
            assertTrue(context.dispatchScheduled(row) is DispatchOutcome.Rejected)
            assertEquals(row, context.messagesDB.get(row.key()))
        } finally { context.messagesDB.delete(row.key()) }
    }
    @Test fun dispatchClaimsLatestPersistedSnapshotAndBlocksFutureEdits() {
        val row = message()
        try {
            context.queueScheduled(row) { }
            val edited = row.copy(body = "latest edit")
            assertEquals(ScheduledQueueResult.QUEUED, context.queueScheduled(edited, row.key()) { })
            ScheduledVerdicts(context).use { journal ->
                val claimed = journal.claimPrepared(row.key()) { context.messagesDB.get(row.key())?.let { it to 1 } }
                assertEquals("latest edit", claimed?.body)
                assertFalse(journal.editQueued(listOf(row.key())) { error("must not run") })
            }
        } finally { context.messagesDB.delete(row.key()) }
    }

    @Test fun staleAlarmAfterSameKeyEditCannotSendBeforeNewDueTime() {
        val firstDue = System.currentTimeMillis() / 1000 + 60
        val row = message().copy(date = firstDue.toInt())
        val edited = row.copy(body = "edited for later", date = (firstDue + 3600).toInt())
        try {
            assertEquals(ScheduledQueueResult.QUEUED, context.queueScheduled(row) { })
            assertEquals(ScheduledQueueResult.QUEUED, context.queueScheduled(edited, row.key()) { })
            val outcome = context.dispatchScheduled(row, fromAlarm = true, nowMillis = firstDue * 1000)
            assertTrue(outcome is DispatchOutcome.Rejected)
            assertEquals(edited, context.messagesDB.get(row.key()))
            ScheduledVerdicts(context).use { assertEquals("QUEUED", it.state(row.key())) }
        } finally { context.messagesDB.delete(row.key()) }
    }

    @Test fun removedSimRefusesBeforeScheduledClaimAndLeavesExactRow() {
        val address = "+12025550189"
        val person = SimpleContact(0, 0, "Client", "",
            arrayListOf(PhoneNumber(address, 0, "", address)), arrayListOf(), arrayListOf())
        val row = message().copy(participants = arrayListOf(person), subscriptionId = 7)
        try {
            assertEquals(ScheduledQueueResult.QUEUED, context.queueScheduled(row) { })
            var queried = false
            val outcome = context.dispatchScheduled(row, activeSimIds = {
                queried = true
                emptySet()
            })
            assertTrue(queried)
            assertEquals(DispatchOutcome.Rejected("Select an active SIM and check SMS access."), outcome)
            assertEquals(row, context.messagesDB.get(row.key()))
            ScheduledVerdicts(context).use { assertEquals("QUEUED", it.state(row.key())) }
        } finally { context.messagesDB.delete(row.key()) }
    }
}
