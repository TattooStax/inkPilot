package org.fossify.messages.services

/**
 * A failed Telephony thread lookup must not prevent a human response from being held.
 * The caller dispatches only after this receipt is committed and its thread is resolved.
 */
internal object HeadlessReplyAdmission {
    fun <T> preserve(
        resolveThread: () -> Long,
        resolveSubscription: () -> Int?,
        persist: (Long?, Int?) -> T,
    ): T {
        val thread = runCatching { resolveThread().takeIf { it > 0 } }.getOrNull()
        val subscription = runCatching { resolveSubscription() }.getOrNull()
        return persist(thread, subscription)
    }
}
