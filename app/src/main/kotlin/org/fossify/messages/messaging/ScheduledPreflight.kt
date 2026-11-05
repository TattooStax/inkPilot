package org.fossify.messages.messaging

/** Preflight rejection keeps a scheduled item queued and never consumes its send claim. */
internal fun scheduledPreflight(subscriptionId: Int, hasRecipient: Boolean, hasAccess: Boolean,
    mediaReadable: () -> Boolean): String? = when {
    subscriptionId < 0 || !hasRecipient || !hasAccess -> "Select an active SIM and check SMS access."
    !mediaReadable() -> "Attachment is unreadable. Original scheduled message retained."
    else -> null
}
