package org.fossify.messages.inkpilot.runtime

import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.Telephony
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.receivers.MmsReceiver
import org.fossify.messages.receivers.SmsReceiver
import org.fossify.messages.receivers.NotificationReplyIdentity
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.transport.LocalMessageTransport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarrierIngressTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun providerInsertFailureRetainsStaffReviewAndDoesNotCreateAgentSource() {
        val sender = "+12025550972"
        val text = "Synthetic provider insert failure"
        val date = 1_700_000_000_000L
        FilteredSmsJournal(context).use { journal ->
            val before = journal.count()
            SmsReceiver().handleMessageSync(context, sender, "", text, date,
                threadId = 972, subscriptionId = 7, status = -1, nativeInsert = { 0L })
            assertEquals(before + 1, journal.count())
            val row = journal.latest().first { it.sender == sender && it.body == text }
            assertEquals("provider_failed", row.reason)
            assertNull(CarrierIngressPolicy.nativeSource(0, 972))
            assertTrue(journal.remove(row.receipt))
            assertEquals(before, journal.count())
        }
    }

    @Test fun filteredArrivalsRemainPrivateRecoverableAndIdempotent() {
        val sender = "+12025550973"
        val text = "Synthetic blocked arrival"
        FilteredSmsJournal(context).use { journal ->
            val before = journal.count()
            try {
                repeat(2) { journal.record(sender, text, 1_700_000_000_001L, 3, "blocked") }
                assertEquals(before + 1, journal.count())
                val row = journal.latest().first { it.sender == sender && it.body == text }
                assertEquals("blocked", row.reason)
                assertTrue(journal.remove(row.receipt))
            } finally {
                journal.latest().filter { it.sender == sender && it.body == text }
                    .forEach { journal.remove(it.receipt) }
            }
            assertEquals(before, journal.count())
        }
    }

    @Test fun exportedSpoofCannotReachMmsCallbackButValidInternalUriIsRecognized() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
        val receiver = requireNotNull(packageInfo.receivers?.firstOrNull { it.name == MmsReceiver::class.java.name })
        assertFalse(receiver.exported)
        MmsReceiver().onMessageReceived(context, Uri.parse("file:///data/user/0/synthetic/private.pdu"))
        MmsReceiver().onMessageReceived(context, Uri.parse("content://media/42"))
        assertEquals(42L, CarrierIngressPolicy.mmsProviderId("content", "mms", listOf("42")))
        assertNull(CarrierIngressPolicy.mmsProviderId("content", "mms", listOf("42", "part")))
    }

    @Test fun nativeReplyIdentityRejectsCrossThreadAndUsesProviderSubscription() {
        assertTrue(SmsRole.isHeld(context))
        val sender = "+12025550975"
        val thread = context.getThreadId(sender)
        assertTrue(thread > 0)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, "Synthetic identity-only reply source")
            put(Telephony.Sms.DATE, 1_700_000_000_002L)
            put(Telephony.Sms.THREAD_ID, thread)
            put(Telephony.Sms.SUBSCRIPTION_ID, 7)
            put(Telephony.Sms.READ, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values))
        try {
            val id = requireNotNull(uri.lastPathSegment?.toLongOrNull())
            val good = CarrierIngressPolicy.ReplyBinding(thread, "sms", id)
            val source = NotificationReplyIdentity.read(context, good)
            assertEquals(thread, source?.threadId)
            assertEquals(sender, source?.recipient)
            assertEquals(7, source?.subscriptionId)
            assertNull(NotificationReplyIdentity.read(context, good.copy(threadId = thread + 1)))
            assertNull(NotificationReplyIdentity.read(context, good.copy(origin = "mms")))
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun eachPermissionsOnlyPartHasDistinctCallbackIdentity() {
        val transport = LocalMessageTransport(context)
        val first = transport.sentResultIntent("+12025550974", "synthetic-attempt", 0)
        val second = transport.sentResultIntent("+12025550974", "synthetic-attempt", 1)
        val another = transport.sentResultIntent("+12025550974", "different-attempt", 0)
        try {
            assertNotEquals(first, second)
            assertNotEquals(first, another)
        } finally { first.cancel(); second.cancel(); another.cancel() }
    }

    @Test fun heldDirectReplySurvivesStoreReopenWithoutAutomaticDispatch() {
        val first = DirectReplyPendingStore(context)
        val held = first.hold(973, "+12025550973", "Synthetic draft reply", 3)
        try {
            val reopened = DirectReplyPendingStore(context).held().single { it.id == held.id }
            assertEquals(3, reopened.subscriptionId)
            assertEquals("Synthetic draft reply", reopened.body)
            assertNull(CarrierIngressPolicy.exactSubscription(reopened.subscriptionId, setOf(7)))
        } finally { DirectReplyPendingStore(context).complete(held.id) }
    }
}
