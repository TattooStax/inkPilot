package org.fossify.messages.inkpilot.data

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.messaging.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledVerdictTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun interruptedDispatchRetainsClaimAndRejectsSecondSendAfterReopen() {
        val key = MessageKey(MessageOrigin.SCHEDULED_SMS, System.nanoTime())
        ScheduledVerdicts(context).use {
            assertEquals("QUEUED", it.state(key))
            assertTrue(it.claim(key, 2))
        }
        ScheduledVerdicts(context).use {
            assertEquals("DISPATCHING", it.state(key))
            assertFalse(it.claim(key, 2))
            it.record(key, 0, false)
            assertEquals("UNKNOWN", it.state(key))
        }
        ScheduledVerdicts(context).use {
            it.record(key, 1, true)
            assertEquals("UNKNOWN", it.state(key))
            assertFalse(it.claim(key, 2))
        }
    }
    @Test fun unverifiedMmsPartKeepsJournalUnknownAfterLaterSuccessfulParts() {
        val key = MessageKey(MessageOrigin.SCHEDULED_MMS, System.nanoTime())
        val unverified = Intent().setData(Uri.parse(key.alarmIdentity() + "/0"))
        ScheduledCallback(key).attach(unverified, 0)
        ScheduledVerdicts(context).use { journal ->
            assertTrue(journal.claim(key, 2))
            context.recordScheduledCallback(unverified, true, nativeRowVerified = false)
            assertEquals("UNKNOWN", journal.state(key))
            journal.record(key, 1, true)
            journal.record(key, 0, true)
            assertEquals("UNKNOWN", journal.state(key))
            assertFalse(journal.claim(key, 2))
        }
    }
    @Test fun sameIdKindsAndIntCollisionsHaveIndependentClaims() {
        val id = System.nanoTime()
        val keys = listOf(MessageKey(MessageOrigin.SCHEDULED_SMS, id), MessageKey(MessageOrigin.SCHEDULED_MMS, id),
            MessageKey(MessageOrigin.SCHEDULED_SMS, id + (1L shl 32)))
        ScheduledVerdicts(context).use { journal -> keys.forEach { assertTrue(journal.claim(it, 1)) } }
        assertEquals(3, keys.map { it.alarmIdentity() }.distinct().size)
        keys.forEach { assertEquals(it, Intent().setData(Uri.parse(it.alarmIdentity())).scheduledKey()) }
        assertNull(Intent().putExtra("scheduled_message_id", id).scheduledKey())
        assertNull(Intent().setData(Uri.parse("inkpilot-scheduled://dispatch/SMS/$id")).scheduledKey())
    }
    @Test fun actualPendingIntentsDoNotAliasKindsOrTruncatedIds() {
        val id = System.nanoTime()
        val keys = listOf(MessageKey(MessageOrigin.SCHEDULED_SMS, id), MessageKey(MessageOrigin.SCHEDULED_MMS, id),
            MessageKey(MessageOrigin.SCHEDULED_SMS, id + (1L shl 32)))
        val pending = keys.map { android.app.PendingIntent.getBroadcast(context, 0,
            context.scheduledAlarmIntent(it, 99), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE) }
        try { assertEquals(3, pending.distinct().size) } finally { pending.forEach { it.cancel() } }
    }
    @Test fun disputedCallbackAfterConfirmationDemotesScheduledJournal() {
        val key = MessageKey(MessageOrigin.SCHEDULED_SMS, System.nanoTime())
        ScheduledVerdicts(context).use { journal ->
            assertTrue(journal.claim(key, 2))
            journal.record(key, 0, true)
            journal.record(key, 1, true)
            assertEquals("CONFIRMED", journal.state(key))
            val duplicate = Intent().setData(Uri.parse(key.alarmIdentity() + "/1"))
            ScheduledCallback(key).attach(duplicate, 1)
            context.recordScheduledCallback(duplicate, false, nativeRowVerified = false)
            assertEquals("UNKNOWN", journal.state(key))
            journal.record(key, 1, true)
            assertEquals("UNKNOWN", journal.state(key))
            assertFalse(journal.claim(key, 2))
        }
    }
    @Test fun allResultsRequiredForConfirmedOrFailed() {
        val key = MessageKey(MessageOrigin.SCHEDULED_MMS, System.nanoTime())
        ScheduledVerdicts(context).use {
            assertTrue(it.claim(key, 2))
            it.record(key, 0, true)
            assertEquals("DISPATCHING", it.state(key))
            val callback = Intent()
            ScheduledCallback(key).attach(callback, 1)
            callback.data = Uri.parse(key.alarmIdentity() + "/1")
            context.recordScheduledCallback(callback, true)
            assertEquals("CONFIRMED", it.state(key))
            assertFalse(it.claim(key, 2))
        }
    }
}
