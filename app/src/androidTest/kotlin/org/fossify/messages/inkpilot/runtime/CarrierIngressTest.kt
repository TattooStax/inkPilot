package org.fossify.messages.inkpilot.runtime

import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.Telephony
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.receivers.MmsReceiver
import org.fossify.messages.receivers.SmsReceiver
import org.fossify.messages.receivers.SmsAdmission
import org.fossify.messages.services.HeadlessReplyAdmission
import org.fossify.messages.receivers.NotificationReplyIdentity
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.transport.LocalMessageTransport
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

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

    @Test fun oversizedMultipartFilterIsDurableAndPrivateFailureQuarantinesNative() {
        val sender = "+12025550979"
        val body = "synthetic".repeat(2_500) // >16,000 characters; no carrier send.
        val name = "inkpilot-oversized-test-${System.nanoTime()}.db"
        try {
            FilteredSmsJournal(context, name).use { journal ->
                val before = journal.count()
                var nativeOnly = 0
                try {
                    val filtered = SmsAdmission.process(
                        classify = { "blocked" },
                        journal = { journal.record(sender, body, 1_700_000_000_003L, 7, it) },
                        normalNative = { fail("A filtered message must not draft") },
                        quarantinedNative = { nativeOnly++ }, onUncertain = {},
                    )
                    assertEquals(SmsAdmission.Result.FILTERED, filtered)
                    assertEquals(body, journal.latest().first { it.sender == sender }.body)
                    assertEquals(before + 1, journal.count())
                    val failed = SmsAdmission.process(
                        classify = { throw IllegalStateException("contacts unavailable") },
                        journal = { fail("No classification exists") },
                        normalNative = { fail("Uncertain filter must quarantine") },
                        quarantinedNative = { nativeOnly++ }, onUncertain = {},
                    )
                    assertEquals(SmsAdmission.Result.QUARANTINED_NATIVE, failed)
                    assertEquals(1, nativeOnly)
                } finally {
                    journal.latest().filter { it.sender == sender }
                        .forEach { journal.remove(it.receipt) }
                }
                assertEquals(before, journal.count())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun receiverAdmissionFailuresPreserveNativeReadOnlyRows() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertTrue(SmsRole.isHeld(context))
        val sender = "+12025550989"
        val token = "inkpilot-admission-${System.nanoTime()}"
        val bodies = listOf("$token contact lookup", "$token journal unavailable")
        try {
            bodies.forEachIndexed { index, body ->
                val result = SmsReceiver().admitDecodedMessage(
                    context, sender, "", body, 1_700_000_000_000L + index, 7, -1,
                    classify = { if (index == 0) error("contact provider denied") else "blocked" },
                    journal = { if (index == 1) error("SQLite unavailable") },
                )
                assertEquals(SmsAdmission.Result.QUARANTINED_NATIVE, result)
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.READ),
                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ?",
                    arrayOf(sender, body), null
                ).use { cursor ->
                    assertNotNull(cursor)
                    assertTrue(cursor!!.moveToFirst())
                    assertTrue(cursor.getLong(0) > 0)
                    assertEquals(body, cursor.getString(1))
                    assertEquals(1, cursor.getInt(2))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            bodies.forEach { body ->
                context.contentResolver.delete(Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ?",
                    arrayOf(sender, body))
            }
        }
    }

    @Test fun staffCanPageAndDisposeMoreThanOneScreenOfFilteredArrivals() {
        val name = "inkpilot-filtered-test-${System.nanoTime()}.db"
        try {
            FilteredSmsJournal(context, name).use { journal ->
                repeat(121) { index ->
                    journal.record("+1202555${(1000 + index)}", "Synthetic filtered $index",
                        1_700_000_000_000L + index, 7, "blocked")
                }
                val receipts = mutableSetOf<String>()
                var cursor: FilteredSmsJournal.Cursor? = null
                do {
                    val page = journal.page(cursor)
                    assertTrue(page.entries.size <= 50)
                    page.entries.forEach { assertTrue(receipts.add(it.receipt)) }
                    cursor = page.next
                } while (cursor != null)
                assertEquals(121, receipts.size)
                assertEquals(121, journal.count().toInt())
                receipts.forEach { assertTrue(journal.remove(it)) }
                assertEquals(0, journal.count())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun staffCanPageAndDisposeMoreThanFiftyHeldReplies() {
        val store = DirectReplyPendingStore(context)
        val created = (0 until 57).map { index ->
            store.hold(980L + index, "+12025550980", "Synthetic held reply $index", 7)
        }
        try {
            val seen = mutableSetOf<String>()
            var cursor: DirectReplyPendingStore.Cursor? = null
            do {
                val page = store.page(cursor)
                assertTrue(page.entries.size <= 50)
                page.entries.filter { it.id in created.map { held -> held.id } }
                    .forEach { assertTrue(seen.add(it.id)) }
                cursor = page.next
            } while (cursor != null)
            assertEquals(57, seen.size)
        } finally { created.forEach { store.complete(it.id) } }
    }

    @Test fun validMmsCallbackConsumesCanonicalNativeProviderRow() {
        // This test writes only its own fixture row on an emulator; never run it on a studio phone.
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertTrue(SmsRole.isHeld(context))
        val sender = "+12025550988"
        val thread = context.getThreadId(sender)
        assertTrue(thread > 0)
        val token = "inkpilot-mms-fixture-${System.nanoTime()}"
        val values = ContentValues().apply {
            put(Telephony.Mms.DATE, 1_700_000_000L)
            put(Telephony.Mms.DATE_SENT, 1_700_000_000L)
            put(Telephony.Mms.THREAD_ID, thread)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.MESSAGE_TYPE, 132) // RetrieveConf
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.TRANSACTION_ID, token)
            put(Telephony.Mms.MESSAGE_ID, token)
            put(Telephony.Mms.MMS_VERSION, 18)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SUBSCRIPTION_ID, 7)
            put(Telephony.Mms.STATUS, 0)
        }
        val uri = requireNotNull(context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values))
        val id = requireNotNull(uri.lastPathSegment?.toLongOrNull())
        val workName = "inkpilot-mms:$id"
        try {
            val address = ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, sender)
                put(Telephony.Mms.Addr.TYPE, 137) // FROM
                put(Telephony.Mms.Addr.CHARSET, 106)
                put(Telephony.Mms.Addr.MSG_ID, id)
            }
            requireNotNull(context.contentResolver.insert(
                Uri.parse("content://mms/$id/addr"), address))
            val part = ContentValues().apply {
                put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
                put(Telephony.Mms.Part.TEXT, "Synthetic native MMS callback fixture")
                put(Telephony.Mms.Part.CHARSET, 106)
                put(Telephony.Mms.Part.MSG_ID, id)
            }
            requireNotNull(context.contentResolver.insert(
                Uri.parse("content://mms/$id/part"), part))
            val canonical = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            assertEquals(id, context.getMMS(canonical)?.id)
            MmsReceiver().onMessageReceived(context, canonical)
            val work = WorkManager.getInstance(context)
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var state: WorkInfo.State? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                state = work.getWorkInfosForUniqueWork(workName).get(5, TimeUnit.SECONDS)
                    .firstOrNull()?.state
                if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED) break
                Thread.sleep(100)
            }
            assertEquals(WorkInfo.State.SUCCEEDED, state)
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork(workName).result.get(5, TimeUnit.SECONDS)
            context.contentResolver.delete(uri, null, null)
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

    @Test fun unresolvedHeadlessThreadKeepsHumanTextInPagedStaffReview() {
        val sender = "+12025550990"
        val body = "Synthetic human respond-via-message text"
        val saved = HeadlessReplyAdmission.preserve(
            resolveThread = { throw IllegalStateException("Telephony thread lookup failed") },
            resolveSubscription = { 7 },
            persist = { thread, subscription ->
                DirectReplyPendingStore(context).hold(thread, sender, body, subscription)
            },
        )
        try {
            val reopened = DirectReplyPendingStore(context).held().single { it.id == saved.held.id }
            assertNull(reopened.threadId)
            assertEquals(sender, reopened.recipient)
            assertEquals(body, reopened.body)
            assertEquals(7, reopened.subscriptionId)
            var cursor: DirectReplyPendingStore.Cursor? = null
            var visible = false
            do {
                val page = DirectReplyPendingStore(context).page(cursor)
                visible = visible || page.entries.any { it.id == saved.held.id }
                cursor = page.next
            } while (!visible && cursor != null)
            assertTrue(visible)
        } finally { DirectReplyPendingStore(context).complete(saved.held.id) }
    }

    @Test fun validThreadWithSavedSimFailureRemainsHeldWithOneActiveSim() {
        val sender = "+12025550991"
        val body = "Synthetic respond-via-message saved SIM failure"
        val admission = HeadlessReplyAdmission.preserve(
            resolveThread = { 991L },
            resolveSubscription = { throw IllegalStateException("saved SIM settings unreadable") },
            persist = { thread, subscription ->
                DirectReplyPendingStore(context).hold(thread, sender, body, subscription)
            },
        )
        try {
            val reopened = DirectReplyPendingStore(context).held().single { it.id == admission.held.id }
            assertEquals(991L, reopened.threadId)
            assertEquals(body, reopened.body)
            assertNull(reopened.subscriptionId)
            assertEquals(HeadlessReplyAdmission.SavedSim.LookupFailed, admission.savedSim)
            assertNull(admission.exactSubscription(setOf(7)))
        } finally { DirectReplyPendingStore(context).complete(admission.held.id) }
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
