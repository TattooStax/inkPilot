package org.fossify.messages.messaging

/** An unreadable draft must never permit deletion of its potentially referenced copy. */
internal fun ComposerStore.recoverSharedMediaAfterCrash() {
    val requests = try {
        prefs.all.keys.mapNotNull { it.toLongOrNull() }.mapNotNull(::load)
    } catch (_: Exception) { return }
    val referenced = requests.flatMap(OutgoingMessage::attachmentUris)
        .mapNotNull(appContext::ownedSharedComposerName).toSet()
    appContext.recoverSharedComposerImages(referenced)
}
