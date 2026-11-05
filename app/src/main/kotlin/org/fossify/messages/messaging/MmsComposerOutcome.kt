package org.fossify.messages.messaging

private fun ComposerStore.completedFile(threadId: Long) =
    java.io.File(claims, "completed-compose-$threadId")

fun ComposerStore.hasCompletedCompose(threadId: Long): Boolean =
    threadId <= 0L && completedFile(threadId).exists()

internal fun ComposerStore.markComposeCompleted(threadId: Long) {
    if (threadId <= 0L) durableMarker(completedFile(threadId))
}

/** Durable terminal callback journal. A crash after this commit can finish cleanup on reopen. */
internal fun ComposerStore.recoverMmsConfirmations() {
    prefs.all.keys.filter { it.startsWith("mms-confirmed:") }.forEach { key ->
        key.removePrefix("mms-confirmed:").toLongOrNull()?.let { threadId ->
            prefs.getString(key, null)?.let { reconcileConfirmed(threadId, it) }
        }
    }
}

private fun ComposerStore.matchesPendingMms(threadId: Long, attemptId: String): Boolean {
    if (attemptId.isBlank()) return false
    val saved = load(threadId) ?: return false
    return saved.attemptId == attemptId && pendingFile(threadId).exists() && wasSubmitted(saved)
}

/** A native provider-confirmed MMS callback may resolve only the exact claimed attempt. */
fun ComposerStore.confirmMmsSent(threadId: Long, attemptId: String): Boolean = locked {
    if (!matchesPendingMms(threadId, attemptId)) return@locked false
    check(prefs.edit().putString("mms-confirmed:$threadId", attemptId).commit())
    reconcileConfirmed(threadId, attemptId)
}

private fun ComposerStore.reconcileConfirmed(threadId: Long, attemptId: String): Boolean {
    val saved = load(threadId)
    if (saved != null && saved.attemptId != attemptId) return false
    markComposeCompleted(threadId)
    if (saved != null) check(appContext.markSharedComposerImagesForDiscard(saved.attachmentUris))
    // Confirmation is journaled already. Delete while the saved URI remains recoverable.
    if (saved != null) appContext.discardSharedComposerImages(saved.attachmentUris)
    check(prefs.edit().remove(threadId.toString()).remove("unknown:$threadId")
        .remove("mms-failed:$threadId").commit())
    val pending = pendingFile(threadId)
    check(!pending.exists() || pending.delete())
    check(prefs.edit().remove("mms-confirmed:$threadId").commit())
    return true
}

/** Failure remains blocked for duplicate-send safety; staff can see the failed provider row. */
fun ComposerStore.recordMmsFailure(threadId: Long, attemptId: String): Boolean = locked {
    if (!matchesPendingMms(threadId, attemptId)) return@locked false
    check(prefs.edit().putString("mms-failed:$threadId", attemptId).commit())
    true
}

fun ComposerStore.isMmsReportedFailed(threadId: Long): Boolean {
    val attempt = load(threadId)?.attemptId ?: return false
    return attempt == prefs.getString("mms-failed:$threadId", null)
}
