package org.fossify.messages.transport

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import inkstax.sms.transport.OutboundAckEvent
import org.fossify.messages.receivers.SendStatusReceiver

/**
 * The phone's verdict on a bridge send (SMS-38 step 4).
 *
 * SmsManager reports the radio's verdict asynchronously
 * through the sent PendingIntents the outbound work sender
 * builds, not through the send call — a returned send call
 * means the message was queued, never that it sent. This
 * receiver is where those reports land for a picked-up work
 * item. Each report names one part of one send, under the
 * pairing that sent it; the receiver turns it into the
 * send's verdict and files it through [fileOutboundVerdict],
 * which holds it in the pending outbound report queue — and
 * enqueues the drain that reports it to the brain — only
 * while the pairing that sent the work is still the stored
 * one, so a verdict arriving after a disconnect and re-pair
 * is never reported to the new endpoint.
 *
 * Every send's verdict is folded through the durable send
 * progress the sender opened: a single-part send's one
 * report completes its progress at once, and a multipart
 * send's verdict is reached only when every part has
 * reported — one part's result is that part's outcome,
 * never the whole message's, and an earlier part can fail
 * while the final part succeeds. A failure carries the
 * sending error code the report wire requires: the platform's
 * error-code extra when the platform supplied one, and the
 * result code itself otherwise — that result code IS the
 * platform's send error on this callback. The intents are
 * mutable precisely so that extra can arrive.
 *
 * A completed verdict stays in its progress entry
 * until it is settled — filed durably, or dropped because
 * its pairing is gone — and only then is the entry cleared;
 * a filing that could not be settled leaves the entry in
 * place: the next part report that names the send returns
 * the stored verdict again, and every pending-report drain
 * recovers retained verdicts before draining, so the
 * scheduled retry retries the filing even when no further
 * callback ever arrives.
 *
 * A broadcast that names no work item, no pairing, or a part
 * outside its send files nothing, and so does a part report
 * whose progress is gone — a verdict reconstructed from a
 * fragment would be a guess, not the phone's verdict.
 * Nothing user-facing happens here: a bridge send has no
 * composer waiting on it, and its outcome belongs to the
 * brain that dispatched the reply, reported through the ack
 * wire — the fork's own send paths keep their own receivers
 * and their own error surfaces.
 *
 * It is registered non-exported and addressed only by the
 * sender's explicit PendingIntents.
 */
class OutboundSendResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)?.takeIf { it.isNotBlank() } ?: return
        val endpointBase = intent.getStringExtra(EXTRA_ENDPOINT_BASE)?.takeIf { it.isNotBlank() } ?: return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.takeIf { it.isNotBlank() } ?: return
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 0)
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        if (partCount < 1 || partIndex !in 0 until partCount) {
            return
        }
        val succeeded = resultCode == Activity.RESULT_OK
        val errorCode = if (succeeded) {
            null
        } else {
            val extraErrorCode = intent.getIntExtra(
                SendStatusReceiver.EXTRA_ERROR_CODE,
                SendStatusReceiver.NO_ERROR_CODE
            )
            if (extraErrorCode != SendStatusReceiver.NO_ERROR_CODE) extraErrorCode else resultCode
        }
        val update = synchronized(OutboundSendProgressGuard.lock) {
            context.recordOutboundSendPart(endpointBase, deviceId, sourceId, partIndex, succeeded, errorCode)
        }
        if (update is OutboundSendProgressUpdate.Completed) {
            val settled = context.fileOutboundVerdict(
                endpointBase = endpointBase,
                deviceId = deviceId,
                sourceId = sourceId,
                event = update.event,
                errorCode = update.errorCode,
                reportedAtMillis = update.reportedAtMillis
            )
            if (settled) {
                synchronized(OutboundSendProgressGuard.lock) {
                    context.clearOutboundSendProgress(endpointBase, deviceId, sourceId)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "org.fossify.messages.transport.EXTRA_OUTBOUND_SOURCE_ID"
        const val EXTRA_ENDPOINT_BASE = "org.fossify.messages.transport.EXTRA_OUTBOUND_ENDPOINT_BASE"
        const val EXTRA_DEVICE_ID = "org.fossify.messages.transport.EXTRA_OUTBOUND_DEVICE_ID"
        const val EXTRA_PART_INDEX = "org.fossify.messages.transport.EXTRA_OUTBOUND_PART_INDEX"
        const val EXTRA_PART_COUNT = "org.fossify.messages.transport.EXTRA_OUTBOUND_PART_COUNT"
    }
}
