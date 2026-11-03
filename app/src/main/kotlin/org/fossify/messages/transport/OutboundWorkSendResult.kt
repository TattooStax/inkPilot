package org.fossify.messages.transport

/** The result of one attempt to send a picked-up work item. */
sealed interface OutboundWorkSendResult {
    /**
     * Nothing was sent: this app does not hold the default-SMS
     * role. A bridge send is defined only under that role — the
     * dedicated posture is the one that may dispatch the brain's
     * replies — and a permissions-only phone keeps its own
     * messenger as default, so it never sends bridge work. The
     * work stays queued at the brain, which still holds it
     * un-acked; the recovery is the role, not a retry.
     */
    data object NotDefaultSmsApp : OutboundWorkSendResult

    /**
     * The platform accepted the submission. That is all this
     * outcome claims: the message is queued with the radio, not
     * sent — the phone's verdict arrives asynchronously through
     * [OutboundSendResultReceiver] and is filed from there,
     * never from this return value.
     */
    data object Submitted : OutboundWorkSendResult

    /**
     * The submission itself failed synchronously, carrying the
     * fork's sending error code. No asynchronous result follows
     * a submission that threw, so the failure verdict is filed
     * with the ack holder before this outcome returns.
     */
    data class Failed(val errorCode: Int) : OutboundWorkSendResult
}
