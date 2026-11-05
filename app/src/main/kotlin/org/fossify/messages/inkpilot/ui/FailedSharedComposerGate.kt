package org.fossify.messages.inkpilot.ui

import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.OutgoingMessage

private const val REATTACH_WARNING = "Shared image could not be kept. Remove it and attach again before sending."

internal fun StudioRoute.Compose?.failedSharedImagePresent(saved: OutgoingMessage): Boolean {
    val route = this ?: return false
    return route.shareCopyFailed && saved.attachmentUris.any { it in route.mediaUris }
}

internal fun failedShareStatus(store: ComposerStore, threadId: Long, failed: Boolean): String =
    if (failed) REATTACH_WARNING else initialComposerStatus(store, threadId)

internal fun composerSendEnabled(store: ComposerStore, threadId: Long, failed: Boolean): Boolean =
    !store.isUnknown(threadId) && !failed
