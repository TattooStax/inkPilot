package inkpilot.agent

import java.util.concurrent.CancellationException

/** A single bounded draft attempt. All persistence and model execution belong to the host. */
class CustomerServiceDrafts(
    private val store: HeldDraftStore,
    private val memory: ContactMemoryReader,
    private val model: LocalDraftModel,
    private val limits: DraftLimits = DraftLimits(),
) {
    fun draft(request: DraftRequest, cancellation: DraftCancellation = DraftCancellation.NONE): DraftOutcome {
        var stage = DraftFailureStage.SOURCE_READ
        var writeAttempted = false
        val key = DraftKey(request.eventId)
        try {
            if (cancellation.isCancelled()) return stopped(DraftStopReason.CANCELLED)
            val source = store.readSource(request.eventId) ?: return stopped(DraftStopReason.SOURCE_MISSING)
            validateSource(request, source)?.let { return stopped(it) }

            stage = DraftFailureStage.RECEIPT_READ
            store.findHeldDraft(key)?.let { receipt ->
                if (cancellation.isCancelled()) return stopped(DraftStopReason.CANCELLED)
                return if (validReceipt(receipt, source, key)) {
                    DraftOutcome.Held(receipt, DraftDisposition.ALREADY_HELD)
                } else stopped(DraftStopReason.INVALID_RECEIPT)
            }

            stage = DraftFailureStage.MEMORY_READ
            val memoryRead = memory.read(source.contactId)
            if (memoryRead.size > limits.memoryEntries) return stopped(DraftStopReason.CONTEXT_LIMIT)
            val notes = memoryRead.toList()
            if (notes.any { it.contactId != source.contactId }) return stopped(DraftStopReason.SCOPE_MISMATCH)
            if (notes.sumOf { it.text.length.toLong() } > limits.memoryCharacters) return stopped(DraftStopReason.CONTEXT_LIMIT)
            if (cancellation.isCancelled()) return stopped(DraftStopReason.CANCELLED)

            stage = DraftFailureStage.MODEL
            val output = model.generate(
                DraftPrompt(DRAFT_INSTRUCTIONS, source.inboundText, notes.map { it.text }, limits.replyCharacters),
                cancellation,
            )
            if (cancellation.isCancelled()) return stopped(DraftStopReason.CANCELLED)
            if (output.length > limits.replyCharacters) return stopped(DraftStopReason.REPLY_LIMIT)
            val body = output.trim()
            if (body.isEmpty()) return stopped(DraftStopReason.EMPTY_REPLY)

            val write = HeldDraftWrite(key, source.threadId, source.contactId, source.recipient, source.revision, body)
            writeAttempted = true
            return when (val result = store.holdIfCurrent(write)) {
                DraftStoreResult.SourceChanged -> stopped(DraftStopReason.SOURCE_CHANGED)
                DraftStoreResult.StaffPaused -> stopped(DraftStopReason.STAFF_PAUSED)
                is DraftStoreResult.Held -> {
                    val validBody = result.disposition == DraftDisposition.ALREADY_HELD || result.receipt.body == body
                    if (validReceipt(result.receipt, source, key) && validBody) {
                        DraftOutcome.Held(result.receipt, result.disposition)
                    } else DraftOutcome.PersistenceUnconfirmed(key)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return if (writeAttempted) DraftOutcome.PersistenceUnconfirmed(key) else stopped(DraftStopReason.CANCELLED)
        } catch (_: CancellationException) {
            return if (writeAttempted) DraftOutcome.PersistenceUnconfirmed(key) else stopped(DraftStopReason.CANCELLED)
        } catch (_: Exception) {
            return if (writeAttempted) DraftOutcome.PersistenceUnconfirmed(key) else DraftOutcome.Failed(stage)
        }
    }

    private fun validateSource(request: DraftRequest, source: DraftSource): DraftStopReason? = when {
        source.eventId != request.eventId || source.threadId != request.threadId ||
            source.contactId != request.contactId -> DraftStopReason.SCOPE_MISMATCH
        source.revision != request.expectedRevision -> DraftStopReason.SOURCE_CHANGED
        source.staffPaused -> DraftStopReason.STAFF_PAUSED
        source.recipient.isBlank() || source.inboundText.isBlank() -> DraftStopReason.INVALID_CONTEXT
        source.inboundText.length > limits.inboundCharacters -> DraftStopReason.CONTEXT_LIMIT
        else -> null
    }

    private fun validReceipt(receipt: HeldDraftReceipt, source: DraftSource, key: DraftKey): Boolean =
        receipt.draftId.isNotBlank() && receipt.key == key && receipt.threadId == source.threadId &&
            receipt.contactId == source.contactId && receipt.recipient == source.recipient &&
            receipt.sourceRevision == source.revision && receipt.body.isNotBlank() &&
            receipt.body.length <= limits.replyCharacters

    private fun stopped(reason: DraftStopReason) = DraftOutcome.Stopped(reason)
}
