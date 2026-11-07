package org.fossify.messages.transport

/** The result of one attempt to send a picked-up work item. */
sealed interface OutboundWorkSendResult {
    /** The radio may already have this source. Wait for the original callback/ACK. */
    data object AlreadySubmitted : OutboundWorkSendResult

    /**
     * Nothing was sent: this device holds no usable credential.
     * Outbound work exists only under a pairing — it was picked
     * up from the paired endpoint, and its verdict is reported
     * back under that same pairing — so an unpaired phone has
     * no bridge work to send and no endpoint to file a verdict
     * with. The recovery is pairing, never a retry.
     */
    data object NotPaired : OutboundWorkSendResult

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

    /** Submitted to the radio. The asynchronous callback determines the carrier verdict. */
    data object Submitted : OutboundWorkSendResult

    /**
     * The send failed before or at submission, carrying the
     * fork's sending error code. No asynchronous result follows
     * a submission that threw, so the failure verdict is filed
     * with the ack holder before this outcome returns; the same
     * outcome covers a multipart send whose part-progress could
     * not be recorded, because its parts' verdicts could never
     * be aggregated honestly.
     */
    data class Failed(val errorCode: Int) : OutboundWorkSendResult
}
