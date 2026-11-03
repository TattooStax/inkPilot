package org.fossify.messages.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
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
 * Sends one picked-up [OutboundWork] item through the platform
 * SMS API under the default-SMS role — step (3) of the outbound
 * send path, performing the send the pickup binding (step 2)
 * hands over.
 *
 * The send runs under the pairing that picked the work up: the
 * stored credential is captured before anything is sent, its
 * non-secret identity (endpoint and device id, never the
 * secret) travels with the send's result callbacks, and every
 * verdict the send produces is filed through
 * [fileOutboundVerdict], which holds it only while that
 * pairing is still the stored one — a disconnect and re-pair
 * landing while the radio is still answering can never pick
 * the verdict up and report it to the new endpoint.
 *
 * The send goes through SmsManager directly, on the platform's
 * default SMS subscription (the posture's subscription pinning
 * stays policy-only, as SMS-3 recorded), and writes no provider
 * row in this slice: the upstream persisting send path files
 * its asynchronous verdict against a provider message id, and
 * a bridge send's verdict is filed against the brain's source
 * id instead — so the insert that path exists to support has
 * nothing to attach to here. The brain holds the record of the
 * reply it dispatched; the fork's own threads do not show
 * bridge sends yet, the same trade-off the permissions-only
 * posture records for its sends.
 *
 * Every part of a multipart send carries its own sent-result
 * PendingIntent, addressed to [OutboundSendResultReceiver]
 * and unique per pairing, source id, and part: one part's
 * result is that part's outcome, never the whole message's,
 * so the receiver aggregates every part's report (through the
 * durable progress the sender opens before submitting) and
 * files one verdict when all parts have reported. The
 * intents are mutable, as the upstream sender's are: the
 * platform attaches its sending error code to the callback
 * intent, and an immutable intent would discard it. A
 * single-part send needs no progress — its one callback is
 * the whole verdict.
 *
 * A submission that throws synchronously produces no
 * asynchronous result, so this sender files that failure
 * verdict itself, through the same pairing-bound filing the
 * receiver uses, with the fork's sending error code. A
 * multipart send whose progress could not be opened is not
 * submitted at all, for the same reason: its parts' verdicts
 * could never be aggregated honestly, so the failure is filed
 * before any part leaves.
 *
 * This call talks to the radio, so it belongs on a background
 * thread.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun Context.sendOutboundWork(work: OutboundWork): OutboundWorkSendResult {
    val credential = storedDeviceCredential()
        ?: return OutboundWorkSendResult.NotPaired
    if (Telephony.Sms.getDefaultSmsPackage(this) != packageName) {
        return OutboundWorkSendResult.NotDefaultSmsApp
    }
    val destination = PhoneNumberUtils.stripSeparators(work.recipient)
    if (destination.isEmpty()) {
        return synchronousFailure(credential, work, TransportOutcome.EMPTY_DESTINATION_ADDRESS)
    }
    return try {
        val smsManager = getSmsManager(SubscriptionManager.getDefaultSmsSubscriptionId())
        val parts = smsManager.divideMessage(work.body)
        if (parts == null || parts.size < 1) {
            return synchronousFailure(credential, work, TransportOutcome.ERROR_SENDING_MESSAGE)
        }
        if (parts.size == 1) {
            val sentIntent = outboundSentResultIntent(credential, work.sourceId, 0, 1)
            @Suppress("DEPRECATION")
            smsManager.sendTextMessage(destination, null, work.body, sentIntent, null)
        } else {
            val opened = synchronized(OutboundSendProgressGuard.lock) {
                beginOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId, parts.size)
            }
            if (!opened) {
                return synchronousFailure(credential, work, TransportOutcome.UNKNOWN_ERROR)
            }
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
 * a failure to carry. Any progress the attempt opened is
 * cleared first, so a late part report — there should be
 * none — has nothing to fold into. The send outcome reports
 * the failure whether or not the verdict could be held.
 */
private fun Context.synchronousFailure(
    credential: DeviceCredential,
    work: OutboundWork,
    errorCode: Int
): OutboundWorkSendResult {
    synchronized(OutboundSendProgressGuard.lock) {
        clearOutboundSendProgress(credential.endpointBase, credential.deviceId, work.sourceId)
    }
    fileOutboundVerdict(
        endpointBase = credential.endpointBase,
        deviceId = credential.deviceId,
        sourceId = work.sourceId,
        event = OutboundAckEvent.PHONE_REPORTED_FAILURE,
        errorCode = errorCode,
        reportedAtMillis = System.currentTimeMillis()
    )
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
