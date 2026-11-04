package org.fossify.messages.transport

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import inkstax.sms.transport.*
import org.fossify.messages.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic provider only: no role change, SmsManager call or device SMS write. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BridgeHistoryTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val credential = DeviceCredential("https://synthetic.example", "device", "synthetic-secret")
    private val work = OutboundWork("source", "+15555550123", "synthetic multipart", 0L)

    @Test fun standaloneBuildRejectsEvenAProvidedCredential() {
        org.junit.Assume.assumeFalse(BuildConfig.OPTIONAL_BRIDGE_ENABLED)
        assertNull(base.storedDeviceCredential())
        assertEquals(OutboundWorkSendResult.NotPaired, base.sendOutboundWork(credential, work))
    }

    @Test fun receiptAndMultipartFailureSurviveReplayAndProgressCleanup() = fixture { context, provider ->
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        val uri = context.createBridgeReceipt(credential, work, work.recipient, 7)!!
        context.contentResolver.query(uri, arrayOf(Sms.BODY, Sms.TYPE, Sms.THREAD_ID), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(work.body, it.getString(0))
            assertEquals(Sms.MESSAGE_TYPE_OUTBOX, it.getInt(1))
            assertEquals(17L, it.getLong(2))
        }
        assertTrue(context.beginOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId, 2))
        assertEquals(OutboundSendProgressUpdate.Pending,
            context.recordOutboundSendPart(credential.endpointBase, credential.deviceId, work.sourceId, 0, false, 42))
        assertEquals(OutboundSendProgressUpdate.Pending,
            context.recordOutboundSendPart(credential.endpointBase, credential.deviceId, work.sourceId, 0, true, null))
        assertFalse(context.beginOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId, 2))
        val result = context.recordOutboundSendPart(credential.endpointBase, credential.deviceId, work.sourceId, 1, true, null)
            as OutboundSendProgressUpdate.Completed
        assertEquals(OutboundAckEvent.PHONE_REPORTED_FAILURE, result.event)
        assertEquals(42, result.errorCode)
        assertTrue(context.reconcileBridgeReceipt(credential.endpointBase, credential.deviceId, work.sourceId, result.event))
        assertEquals(Sms.MESSAGE_TYPE_FAILED, provider.row!!.getAsInteger(Sms.TYPE))
        context.clearOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId)
        val reopened = ReceiptContext(base, provider, context.prefix)
        assertEquals(BridgeSourceClaim.EXISTING, reopened.claimBridgeSource(credential.copy(), work.sourceId))
        assertNull(reopened.createBridgeReceipt(credential, work, work.recipient, 7))
        assertEquals(1, provider.inserts)
    }

    @Test fun crashBeforeReceiptAndInsertFailureNeverReopenSource() = fixture { context, provider ->
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        val reopened = ReceiptContext(base, provider, context.prefix)
        assertEquals(BridgeSourceClaim.EXISTING, reopened.claimBridgeSource(credential, work.sourceId))
        provider.failInsert = true
        assertNull(context.createBridgeReceipt(credential, work, work.recipient, 7))
        assertEquals(BridgeSourceClaim.EXISTING, reopened.claimBridgeSource(credential, work.sourceId))
        assertEquals(0, provider.inserts)
    }

    @Test fun reconciliationFailureRetriesWithoutReinsertion() = fixture { context, provider ->
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        assertNotNull(context.createBridgeReceipt(credential, work, work.recipient, 7))
        provider.failUpdate = true
        assertFalse(context.reconcileBridgeReceipt(credential.endpointBase, credential.deviceId, work.sourceId, OutboundAckEvent.PHONE_ACKED_SENT))
        provider.failUpdate = false
        assertTrue(context.reconcileBridgeReceipt(credential.endpointBase, credential.deviceId, work.sourceId, OutboundAckEvent.PHONE_ACKED_SENT))
        assertEquals(Sms.MESSAGE_TYPE_SENT, provider.row!!.getAsInteger(Sms.TYPE))
        assertEquals(1, provider.inserts)
    }

    @Test fun disconnectedRecoveryWorkerRetriesUntilNativeStatusIsStored() = fixture { context, provider ->
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        assertNotNull(context.createBridgeReceipt(credential, work, work.recipient, 7))
        assertTrue(context.storeCompletedOutboundVerdict(credential.endpointBase, credential.deviceId,
            work.sourceId, OutboundAckEvent.PHONE_ACKED_SENT, null, 123L))
        val worker = androidx.work.testing.TestWorkerBuilder<PendingReportDrainWorker>(
            context, java.util.concurrent.Executor { it.run() }).build()
        provider.failUpdate = true
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(1, context.storedCompletedOutboundVerdicts().size)
        provider.failUpdate = false
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertTrue(context.storedCompletedOutboundVerdicts().isEmpty())
        assertEquals(Sms.MESSAGE_TYPE_SENT, provider.row!!.getAsInteger(Sms.TYPE))
        assertEquals(BridgeSourceClaim.EXISTING, context.claimBridgeSource(credential, work.sourceId))
    }

    @Test fun zeroUpdateRetainsExistingReceiptUntilRecoverySucceeds() = fixture { context, provider ->
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        assertNotNull(context.createBridgeReceipt(credential, work, work.recipient, 7))
        assertTrue(context.storeCompletedOutboundVerdict(credential.endpointBase, credential.deviceId,
            work.sourceId, OutboundAckEvent.PHONE_ACKED_SENT, null, 123L))
        val worker = androidx.work.testing.TestWorkerBuilder<PendingReportDrainWorker>(
            context, java.util.concurrent.Executor { it.run() }).build()
        provider.zeroUpdate = true
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(Sms.MESSAGE_TYPE_OUTBOX, provider.row!!.getAsInteger(Sms.TYPE))
        assertEquals(1, context.storedCompletedOutboundVerdicts().size)
        provider.failReceiptRead = true
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(1, context.storedCompletedOutboundVerdicts().size)
        provider.failReceiptRead = false
        provider.zeroUpdate = false
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(Sms.MESSAGE_TYPE_SENT, provider.row!!.getAsInteger(Sms.TYPE))
        assertTrue(context.storedCompletedOutboundVerdicts().isEmpty())
    }

    @Test fun verifiedDeletedReceiptSettlesWithoutRecreatingHistory() = fixture { context, provider ->
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        assertNotNull(context.createBridgeReceipt(credential, work, work.recipient, 7))
        assertTrue(context.storeCompletedOutboundVerdict(credential.endpointBase, credential.deviceId,
            work.sourceId, OutboundAckEvent.PHONE_ACKED_SENT, null, 123L))
        provider.row = null
        val worker = androidx.work.testing.TestWorkerBuilder<PendingReportDrainWorker>(
            context, java.util.concurrent.Executor { it.run() }).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertTrue(context.storedCompletedOutboundVerdicts().isEmpty())
        assertNull(provider.row)
        assertEquals(1, provider.inserts)
        assertEquals(BridgeSourceClaim.EXISTING, context.claimBridgeSource(credential, work.sourceId))
    }

    @Test fun changedDefaultAfterReceiptPreventsRadioHandoff() = fixture { context, provider ->
        var snapshot = BridgeSubscriptionSnapshot(7, setOf(7, 8))
        val selection = BridgeSubscriptionSelection.capture { snapshot }!!
        assertEquals(BridgeSourceClaim.OWNED, context.claimBridgeSource(credential, work.sourceId))
        assertNotNull(context.createBridgeReceipt(credential, work, work.recipient, selection.subscriptionId))
        assertEquals(7, provider.row!!.getAsInteger(Sms.SUBSCRIPTION_ID))
        snapshot = BridgeSubscriptionSnapshot(8, setOf(7, 8))
        var submissions = 0
        assertFalse(selection.dispatchIfCurrent { submissions++ })
        assertEquals(0, submissions)
        assertEquals(7, provider.row!!.getAsInteger(Sms.SUBSCRIPTION_ID))
    }

    private fun fixture(block: (ReceiptContext, SyntheticProvider) -> Unit) {
        val provider = SyntheticProvider().apply { attachInfo(base, ProviderInfo().apply { authority = "sms;mms-sms"; applicationInfo = base.applicationInfo }) }
        val context = ReceiptContext(base, provider, "test_bridge_" + UUID.randomUUID() + "_")
        try { block(context, provider) } finally {
            base.deleteSharedPreferences(context.prefix + "bridge_source_receipts")
            base.deleteSharedPreferences(context.prefix + "outbound_send_progress")
        }
    }
    private class ReceiptContext(base: Context, provider: SyntheticProvider, val prefix: String) : ContextWrapper(base) {
        private val resolver = ContentResolver.wrap(provider)
        override fun getApplicationContext(): Context = this
        override fun getContentResolver() = resolver
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
    }
    private class SyntheticProvider : ContentProvider() {
        var row: ContentValues? = null
        var inserts = 0
        var failInsert = false
        var failUpdate = false
        var zeroUpdate = false
        var failReceiptRead = false
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (failInsert) return null
            row = ContentValues(values).apply { put(Sms._ID, 9L) }
            inserts++
            return Uri.parse("content://sms/9")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failUpdate) error("synthetic provider unavailable")
            if (zeroUpdate || row == null) return 0
            row!!.putAll(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (uri.path?.contains("threadID") == true) return MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(17L)) }
            if (failReceiptRead) error("synthetic query refusal")
            val columns = projection ?: arrayOf(Sms._ID)
            return MatrixCursor(columns).apply { row?.let { value -> addRow(columns.map { value.get(it) }.toTypedArray()) } }
        }
    }
}
