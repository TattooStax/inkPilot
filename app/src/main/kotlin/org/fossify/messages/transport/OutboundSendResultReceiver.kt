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
 * through the sent PendingIntent the outbound work sender
 * builds, not through the send call — a returned send call
 * means the message was queued, never that it sent. This
 * receiver is where that verdict lands for a picked-up work
 * item: it reads the brain's source id from the intent the
 * sender addressed to it, turns the result into the ack event
 * the seam defines, and hands it to the verdict caller, which
 * holds it in the pending outbound report queue and enqueues
 * the drain that reports it to the brain. A failure carries
 * the sending error code the report wire requires: the
 * platform's error-code extra when the platform supplied one,
 * and the result code itself otherwise — that result code IS
 * the platform's send error on this callback.
 *
 * Multipart sends carry this intent on their final part only,
 * so one send produces exactly one verdict here. A broadcast
 * without a source id names no work item and files nothing.
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
        if (resultCode == Activity.RESULT_OK) {
            context.holdAndDrainOutboundVerdict(
                sourceId = sourceId,
                event = OutboundAckEvent.PHONE_ACKED_SENT,
                errorCode = null,
                reportedAtMillis = System.currentTimeMillis()
            )
        } else {
            val extraErrorCode = intent.getIntExtra(
                SendStatusReceiver.EXTRA_ERROR_CODE,
                SendStatusReceiver.NO_ERROR_CODE
            )
            context.holdAndDrainOutboundVerdict(
                sourceId = sourceId,
                event = OutboundAckEvent.PHONE_REPORTED_FAILURE,
                errorCode = if (extraErrorCode != SendStatusReceiver.NO_ERROR_CODE) {
                    extraErrorCode
                } else {
                    resultCode
                },
                reportedAtMillis = System.currentTimeMillis()
            )
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "org.fossify.messages.transport.EXTRA_OUTBOUND_SOURCE_ID"
    }
}
