package org.fossify.messages.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.OutboundWork
import inkstax.sms.transport.TransportOutcome
import org.fossify.messages.messaging.SmsException
import org.fossify.messages.messaging.getSmsManager

/**
 * Sends one picked-up [OutboundWork] item through the platform
 * SMS API under the default-SMS role — step (3) of the outbound
 * send path, performing the send the pickup binding (step 2)
 * hands over.
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
 * The sent-result PendingIntent is addressed to
 * [OutboundSendResultReceiver] and carries the work's source
 * id as an extra; its identity is unique per source id, so
 * concurrent sends never overwrite each other's result
 * routing. A multipart message gets exactly one sent intent,
 * on its final part — one send, one verdict — following the
 * upstream sender's own shape for delivery reports. The
 * verdict that intent produces is the final part's result;
 * earlier parts carry no intent and file nothing.
 *
 * A submission that throws synchronously produces no
 * asynchronous result, so this sender files that failure
 * verdict itself, through the same holder the receiver uses,
 * with the fork's sending error code. Every other failure
 * arrives asynchronously and is filed by the receiver.
 *
 * This call talks to the radio, so it belongs on a background
 * thread.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun Context.sendOutboundWork(work: OutboundWork): OutboundWorkSendResult {
    if (Telephony.Sms.getDefaultSmsPackage(this) != packageName) {
        return OutboundWorkSendResult.NotDefaultSmsApp
    }
    val destination = PhoneNumberUtils.stripSeparators(work.recipient)
    if (destination.isEmpty()) {
        return synchronousFailure(work, TransportOutcome.EMPTY_DESTINATION_ADDRESS)
    }
    return try {
        val smsManager = getSmsManager(SubscriptionManager.getDefaultSmsSubscriptionId())
        val parts = smsManager.divideMessage(work.body)
        if (parts == null || parts.size < 1) {
            return synchronousFailure(work, TransportOutcome.ERROR_SENDING_MESSAGE)
        }
        val sentIntent = outboundSentResultIntent(work.sourceId)
        if (parts.size == 1) {
            @Suppress("DEPRECATION")
            smsManager.sendTextMessage(destination, null, work.body, sentIntent, null)
        } else {
            val sentIntents = ArrayList<PendingIntent?>(List(parts.size - 1) { null } + sentIntent)
            @Suppress("DEPRECATION")
            smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
        }
        OutboundWorkSendResult.Submitted
    } catch (e: SmsException) {
        synchronousFailure(work, e.errorCode)
    } catch (e: Exception) {
        synchronousFailure(work, TransportOutcome.UNKNOWN_ERROR)
    }
}

/**
 * Files the failure verdict for a submission that threw: no
 * asynchronous result follows, so the verdict is reached here,
 * with the sending error code the wire report requires a
 * failure to carry. The send outcome reports the failure
 * whether or not the verdict could be held — an unpaired
 * phone holds nothing, exactly as the verdict caller defines.
 */
private fun Context.synchronousFailure(work: OutboundWork, errorCode: Int): OutboundWorkSendResult {
    holdAndDrainOutboundVerdict(
        sourceId = work.sourceId,
        event = OutboundAckEvent.PHONE_REPORTED_FAILURE,
        errorCode = errorCode,
        reportedAtMillis = System.currentTimeMillis()
    )
    return OutboundWorkSendResult.Failed(errorCode)
}

/**
 * One sent-result PendingIntent per work item. The data Uri
 * makes the intent's identity unique per source id, so two
 * sends in flight never overwrite each other's result routing;
 * the source id itself travels as an extra, because the Uri
 * carries its encoded spelling, not the id the verdict is
 * filed against.
 */
private fun Context.outboundSentResultIntent(sourceId: String): PendingIntent {
    val intent = Intent(this, OutboundSendResultReceiver::class.java).apply {
        data = Uri.parse("inkstax-sms://outbound-result/${Uri.encode(sourceId)}")
        putExtra(OutboundSendResultReceiver.EXTRA_SOURCE_ID, sourceId)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(this, 0, intent, flags)
}
