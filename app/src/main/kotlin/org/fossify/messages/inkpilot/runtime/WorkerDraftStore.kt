package org.fossify.messages.inkpilot.runtime

import inkpilot.agent.DraftKey
import inkpilot.agent.HeldDraftStore
import inkpilot.agent.HeldDraftWrite
import org.fossify.messages.inkpilot.data.StudioRepository

/** Worker-instance epoch, independent of semantic source revision and regeneration permission. */
internal class WorkerDraftStore(private val repository: StudioRepository, private val token: String) :
    HeldDraftStore by repository {
    override fun holdIfCurrent(write: HeldDraftWrite) = repository.holdForWorker(token, write)
}

/** A missing/invalid receipt after an ambiguous hold must retain WorkManager's retry obligation. */
internal fun requireConfirmedDraftPersistence(store: HeldDraftStore, key: DraftKey) {
    val source = store.readSource(key.eventId)
    val receipt = store.findHeldDraft(key)
    if (source == null || receipt == null || receipt.key != key || receipt.draftId.isBlank() ||
        receipt.sourceRevision != source.revision || receipt.contactId != source.contactId ||
        receipt.threadId != source.threadId || receipt.recipient != source.recipient || receipt.body.isBlank() || receipt.body.length > 800
    ) throw UnresolvedDraftPersistence()
}

internal class UnresolvedDraftPersistence : IllegalStateException("Draft persistence requires retry")
