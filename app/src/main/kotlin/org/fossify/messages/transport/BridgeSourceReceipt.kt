package org.fossify.messages.transport

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SubscriptionManager
import android.util.Base64
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.OutboundWork
import org.fossify.messages.extensions.getThreadId

/** Permanent source tombstones survive progress/ack cleanup and disconnect.
 * A claimed source is never submitted again, including after a crash before the
 * radio call. Unknown submission is intentionally not retried automatically.
 * Callers serialize claim, receipt creation and submission with CredentialGuard.
 */
internal enum class BridgeSourceClaim { OWNED, EXISTING, STORAGE_FAILED }

private fun receiptKey(credential: DeviceCredential, sourceId: String): String =
    listOf(credential.endpointBase, credential.deviceId, sourceId).joinToString(".") {
        Base64.encodeToString(it.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

private fun Context.receiptPrefs() = getSharedPreferences("bridge_source_receipts", Context.MODE_PRIVATE)

internal fun Context.claimBridgeSource(credential: DeviceCredential, sourceId: String): BridgeSourceClaim {
    val key = receiptKey(credential, sourceId)
    val prefs = receiptPrefs()
    if (prefs.contains(key)) return BridgeSourceClaim.EXISTING
    val stored = prefs.edit().putString(key, "claimed").commit()
    return if (stored && prefs.getString(key, null) == "claimed") BridgeSourceClaim.OWNED
    else BridgeSourceClaim.STORAGE_FAILED
}

/** Persist and verify a native outbox receipt before any part reaches the radio.
 * A crash between provider insert and URI commit may leave an unsent outbox row;
 * the source tombstone prevents replay from producing another SMS.
 */
internal fun Context.createBridgeReceipt(credential: DeviceCredential, work: OutboundWork, destination: String): Uri? {
    val key = receiptKey(credential, work.sourceId)
    val prefs = receiptPrefs()
    if (prefs.getString(key, null) != "claimed") return null
    return runCatching {
        val threadId = getThreadId(destination)
        if (threadId <= 0L) return null
        val values = ContentValues().apply {
            put(Sms.ADDRESS, destination)
            put(Sms.BODY, work.body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.THREAD_ID, threadId)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.SUBSCRIPTION_ID, SubscriptionManager.getDefaultSmsSubscriptionId())
        }
        val uri = contentResolver.insert(Sms.CONTENT_URI, values) ?: return null
        if (!prefs.edit().putString(key, uri.toString()).commit() ||
            prefs.getString(key, null) != uri.toString()) return null
        contentResolver.query(uri, arrayOf(Sms._ID), null, null, null)?.use {
            if (it.moveToFirst()) uri else null
        }
    }.getOrNull()
}

/** Reconcile native history before acknowledging the aggregated radio verdict.
 * An insert failure has no URI and sent nothing; a deleted receipt is never
 * recreated (that would undo a user deletion). Transient update failure retains
 * progress so the existing verdict recovery retries reconciliation.
 */
internal fun Context.reconcileBridgeReceipt(
    endpointBase: String, deviceId: String, sourceId: String, event: OutboundAckEvent
): Boolean {
    val credential = DeviceCredential(endpointBase, deviceId, "")
    val value = receiptPrefs().getString(receiptKey(credential, sourceId), null) ?: return true
    if (value == "claimed") return true
    return runCatching {
        val uri = Uri.parse(value)
        val values = ContentValues().apply {
            put(Sms.TYPE, if (event == OutboundAckEvent.PHONE_ACKED_SENT) Sms.MESSAGE_TYPE_SENT else Sms.MESSAGE_TYPE_FAILED)
        }
        contentResolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}
