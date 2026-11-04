package org.fossify.messages.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.OutboundWork
import inkstax.sms.transport.TransportOutcome
import org.fossify.commons.helpers.isSPlus
import org.fossify.messages.messaging.SmsException
import org.fossify.messages.messaging.getSmsManager

/**
 * Sends picked-up work only in an opted-in build holding the default SMS role.
 * A durable pairing/source claim and verified native outbox receipt precede
 * radio submission. Claims survive ack cleanup and process death, so replay
 * never resends an ambiguous submission.
 *
 * Every part has a pairing/source/part-specific mutable result PendingIntent.
 * Durable progress aggregates their verdicts and reconciles the native row
 * before pairing-bound ack filing. Disconnect cannot redirect a report to
 * a replacement pairing. Synchronous failures use the same durable filing.
 *
 * This call talks to the radio and belongs on a background thread.
 */
fun Context.sendOutboundWork(work: OutboundWork): OutboundWorkSendResult {
    val credential = storedDeviceCredential()
        ?: return OutboundWorkSendResult.NotPaired
    return sendOutboundWork(credential, work)
}

/**
 * The same send performed with a credential the caller already
 * holds: a caller that acts under a pairing — the pickup run
 * that took this work from that pairing's endpoint — sends
 * with that pairing's credential, never one a disconnect and
 * re-pair may have stored since, so work taken from one
 * pairing is only ever sent and acked under that pairing.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun Context.sendOutboundWork(
    credential: DeviceCredential,
    work: OutboundWork
): OutboundWorkSendResult {
    return synchronized(CredentialGuard.lock) {
        if (!org.fossify.messages.BuildConfig.OPTIONAL_BRIDGE_ENABLED || storedDeviceCredential() != credential) {
            OutboundWorkSendResult.NotPaired
        } else {
            sendGuardedOutboundWork(credential, work)
        }
    }
}

@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun Context.sendGuardedOutboundWork(
    credential: DeviceCredential,
    work: OutboundWork
): OutboundWorkSendResult {
    if (!org.fossify.messages.helpers.SmsRole.isHeld(this)) {
        return OutboundWorkSendResult.NotDefaultSmsApp
    }
    when (claimBridgeSource(credential, work.sourceId)) {
        BridgeSourceClaim.EXISTING -> return OutboundWorkSendResult.AlreadyHandled
        BridgeSourceClaim.STORAGE_FAILED -> return OutboundWorkSendResult.Failed(TransportOutcome.ERROR_PERSISTING_MESSAGE)
        BridgeSourceClaim.OWNED -> Unit
    }
    val destination = PhoneNumberUtils.stripSeparators(work.recipient)
    if (destination.isEmpty()) {
        return synchronousFailure(credential, work, TransportOutcome.EMPTY_DESTINATION_ADDRESS)
    }
    return try {
        if (createBridgeReceipt(credential, work, destination) == null) {
            return synchronousFailure(credential, work, TransportOutcome.ERROR_PERSISTING_MESSAGE)
        }
        val smsManager = getSmsManager(SubscriptionManager.getDefaultSmsSubscriptionId())
        val parts = smsManager.divideMessage(work.body)
        if (parts == null || parts.size < 1) {
            return synchronousFailure(credential, work, TransportOutcome.ERROR_SENDING_MESSAGE)
        }
        val opened = synchronized(OutboundSendProgressGuard.lock) {
            beginOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId, parts.size)
        }
        if (!opened) {
            return synchronousFailure(credential, work, TransportOutcome.UNKNOWN_ERROR)
        }
        if (parts.size == 1) {
            val sentIntent = outboundSentResultIntent(credential, work.sourceId, 0, 1)
            @Suppress("DEPRECATION")
            smsManager.sendTextMessage(destination, null, work.body, sentIntent, null)
        } else {
            val sentIntents = ArrayList(
                parts.indices.map { outboundSentResultIntent(credential, work.sourceId, it, parts.size) }
            )
            @Suppress("DEPRECATION")
            smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
        }
        OutboundWorkSendResult.Submitted
    } catch (e: SmsException) {
        synchronousFailure(credential, work, e.errorCode)
    } catch (e: Exception) {
        synchronousFailure(credential, work, TransportOutcome.UNKNOWN_ERROR)
    }
}

/**
 * Files the failure verdict for a send that never reached the
 * radio: no asynchronous result follows, so the verdict is
 * reached here, under the pairing the send was attempted
 * under, with the sending error code the wire report requires
 * a failure to carry. The verdict is first retained as a
 * completed progress entry, replacing any progress the
 * attempt opened, and is cleared only once the filing
 * settles — a hold that could not be persisted would
 * otherwise lose a verdict no callback can ever re-drive,
 * and the drain's recovery files a retained one on its next
 * run. When the retention itself cannot be stored, the
 * filing is still attempted directly, as before. The send
 * outcome reports the failure whether or not the verdict
 * could be held.
 */
private fun Context.synchronousFailure(
    credential: DeviceCredential,
    work: OutboundWork,
    errorCode: Int
): OutboundWorkSendResult {
    val reportedAtMillis = System.currentTimeMillis()
    val retained = synchronized(OutboundSendProgressGuard.lock) {
        storeCompletedOutboundVerdict(
            endpointBase = credential.endpointBase,
            deviceId = credential.deviceId,
            sourceId = work.sourceId,
            event = OutboundAckEvent.PHONE_REPORTED_FAILURE,
            errorCode = errorCode,
            reportedAtMillis = reportedAtMillis
        )
    }
    val settled = fileOutboundVerdict(
        endpointBase = credential.endpointBase,
        deviceId = credential.deviceId,
        sourceId = work.sourceId,
        event = OutboundAckEvent.PHONE_REPORTED_FAILURE,
        errorCode = errorCode,
        reportedAtMillis = reportedAtMillis
    )
    if (retained && !settled) enqueueBridgeReceiptRecovery()
    if (retained && settled) {
        synchronized(OutboundSendProgressGuard.lock) {
            clearOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId)
        }
    }
    return OutboundWorkSendResult.Failed(errorCode)
}

/**
 * One sent-result PendingIntent per part of a send. The data
 * Uri makes the intent's identity unique per endpoint,
 * device, source id, and part, so two sends in flight never
 * overwrite each other's result routing — not even the same
 * device id and source id under two endpoints, where an
 * update-current intent would otherwise replace the old
 * send's callback extras with the new send's; the verdict's
 * facts travel as extras, because the Uri carries encoded
 * spellings, not the values the verdict is filed against. The intent is mutable
 * on the versions that distinguish it, following the upstream
 * sender: the platform fills in its error code on the
 * callback, and an immutable intent would silently drop it.
 */
private fun Context.outboundSentResultIntent(
    credential: DeviceCredential,
    sourceId: String,
    partIndex: Int,
    partCount: Int
): PendingIntent {
    val intent = Intent(this, OutboundSendResultReceiver::class.java).apply {
        data = Uri.parse(
            "inkstax-sms://outbound-result/${Uri.encode(credential.endpointBase)}" +
                "/${Uri.encode(credential.deviceId)}/${Uri.encode(sourceId)}/$partIndex"
        )
        putExtra(OutboundSendResultReceiver.EXTRA_SOURCE_ID, sourceId)
        putExtra(OutboundSendResultReceiver.EXTRA_ENDPOINT_BASE, credential.endpointBase)
        putExtra(OutboundSendResultReceiver.EXTRA_DEVICE_ID, credential.deviceId)
        putExtra(OutboundSendResultReceiver.EXTRA_PART_INDEX, partIndex)
        putExtra(OutboundSendResultReceiver.EXTRA_PART_COUNT, partCount)
    }
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (isSPlus()) {
        flags = flags or PendingIntent.FLAG_MUTABLE
    }
    return PendingIntent.getBroadcast(this, 0, intent, flags)
}
