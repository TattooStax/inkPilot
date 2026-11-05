package org.fossify.messages.inkpilot.ui

import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.messages.messaging.SmsPartState
import org.fossify.messages.models.Message

private const val STALE_OUTBOX_MILLIS = 600_000L

/** Legacy callbacks have no part identity; a stale native outbox needs manual review. */
internal fun staleUntrackedSmsOutcome(
    nativeType: Int, millis: Long, now: Long = System.currentTimeMillis(),
): SmsPartState? = SmsPartState.UNKNOWN.takeIf {
    nativeType == Sms.MESSAGE_TYPE_OUTBOX && now - millis >= STALE_OUTBOX_MILLIS
}

/** SMS part outcomes are durable; a mixed result is never displayed as fully sent. */
internal fun Message.deliveryLabel(
    partState: SmsPartState? = null, multipart: Boolean = false,
): String = when (partState) {
    SmsPartState.UNKNOWN, SmsPartState.PARTIAL_FAILED, SmsPartState.READY_SENT, SmsPartState.READY_FAILED ->
        "Outcome unknown · check recipient before retry"
    SmsPartState.DISPATCHING -> "Sending"
    SmsPartState.FAILED -> "Failed · check recipient before retry"
    SmsPartState.SENT -> when {
        type == Sms.MESSAGE_TYPE_SENT && multipart && status == Sms.STATUS_COMPLETE ->
            "Last part delivered · full delivery unverified"
        type == Sms.MESSAGE_TYPE_SENT -> nativeDeliveryLabel()
        type == Sms.MESSAGE_TYPE_OUTBOX -> "Sent" // Room may lag a verified native row after process death.
        else -> "Outcome unknown · check recipient before retry"
    }
    else -> nativeDeliveryLabel()
}

/** SMS delivery-report status and MMS message-box values are separate provider namespaces. */
private fun Message.nativeDeliveryLabel(): String = if (isMMS) mmsDeliveryLabel() else smsDeliveryLabel()

private fun Message.mmsDeliveryLabel(): String = when (type) {
    Mms.MESSAGE_BOX_INBOX -> "Received"
    Mms.MESSAGE_BOX_SENT -> "Sent"
    Mms.MESSAGE_BOX_DRAFTS -> "Draft"
    Mms.MESSAGE_BOX_OUTBOX -> "Sending"
    Mms.MESSAGE_BOX_FAILED -> "Failed"
    else -> "Unknown state"
}

private fun Message.smsDeliveryLabel(): String = when (type) {
    Sms.MESSAGE_TYPE_INBOX -> "Received"
    Sms.MESSAGE_TYPE_SENT -> when {
        status == Sms.STATUS_COMPLETE -> "Delivered"
        status >= Sms.STATUS_FAILED -> "Delivery failed"
        status >= Sms.STATUS_PENDING -> "Delivery pending"
        else -> "Sent"
    }
    Sms.MESSAGE_TYPE_DRAFT -> "Draft"
    Sms.MESSAGE_TYPE_OUTBOX -> "Sending"
    Sms.MESSAGE_TYPE_FAILED -> "Failed"
    Sms.MESSAGE_TYPE_QUEUED -> "Queued"
    else -> "Unknown state"
}
