package org.fossify.messages.transport

import inkstax.sms.transport.OutboundAckEvent

/**
 * One completed send's retained verdict: the pairing that
 * sent the work, the work's source id, and the verdict the
 * parts reached, recovered from a done progress entry so it
 * can be filed after an interruption.
 */
data class CompletedOutboundVerdict(
    val endpointBase: String,
    val deviceId: String,
    val sourceId: String,
    val event: OutboundAckEvent,
    val errorCode: Int?,
    val reportedAtMillis: Long
)
