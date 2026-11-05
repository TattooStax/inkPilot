package org.fossify.messages.inkpilot.data

data class StudioContact(
    val id: String,
    val number: String,
    val displayName: String,
    val memory: String,
    val paused: Boolean,
    val revision: Long,
    val latestEventId: String?,
    val lastMessageAt: Long,
)

data class ReviewDraft(
    val draftId: String,
    val eventId: String,
    val threadId: Long,
    val contactId: String,
    val recipient: String,
    val displayName: String,
    val inboundText: String,
    val body: String,
    val sourceRevision: Long,
    val subscriptionId: Int,
    val createdAt: Long,
    val canReview: Boolean,
    val status: String = "HELD",
    val sendBody: String? = null,
    val staffEditBody: String? = null,
)

enum class SendStatus { SENDING, SENT, FAILED, UNKNOWN }

/** A persisted staff-authorized send attempt. Reopening the app must never resend it. */
data class SendClaim(
    val draftId: String,
    val eventId: String,
    val threadId: Long,
    val contactId: String,
    val recipient: String,
    val body: String,
    val subscriptionId: Int,
    val sourceRevision: Long,
    val claimedAt: Long,
    val providerId: Long?,
    val status: SendStatus,
    val error: String?,
)
