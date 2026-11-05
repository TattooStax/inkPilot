package inkpilot.agent

/** Contract simulation only. This in-memory fake is not a durable host implementation. */
internal class DraftFixture {
    val request = DraftRequest("event-1", "thread-1", "contact-1", 7)
    val initialSource = DraftSource(
        "event-1", "thread-1", "contact-1", "+15551234567", 7,
        "Can I book a consultation?", false,
    )
    val store = FakeDraftStore(initialSource)
    var notes = listOf(ContactMemory("contact-1", "Interested in a traditional rose."))
    var modelCalls = 0
    var lastPrompt: DraftPrompt? = null
    var generate: (DraftPrompt, DraftCancellation) -> String = { _, _ -> "What day would suit you?" }

    fun coordinator(limits: DraftLimits = DraftLimits(), safetyGate: DraftSafetyGate = DraftSafetyGate.NONE) = CustomerServiceDrafts(
        store,
        ContactMemoryReader { notes },
        LocalDraftModel { prompt, cancellation ->
            modelCalls++
            lastPrompt = prompt
            generate(prompt, cancellation)
        },
        limits,
        safetyGate,
    )
}

internal class FakeDraftStore(var source: DraftSource?) : HeldDraftStore {
    val receipts = mutableMapOf<DraftKey, HeldDraftReceipt>()
    var holdAttempts = 0
    var inserts = 0
    var beforeHold: ((HeldDraftWrite) -> Unit)? = null
    var throwAfterCommit = false
    var receiptTransform: (HeldDraftReceipt) -> HeldDraftReceipt = { it }

    @Synchronized
    override fun readSource(eventId: String) = source?.takeIf { it.eventId == eventId }

    @Synchronized
    override fun findHeldDraft(key: DraftKey) = receipts[key]

    @Synchronized
    override fun holdIfCurrent(write: HeldDraftWrite): DraftStoreResult {
        holdAttempts++
        beforeHold?.invoke(write)
        val current = source ?: return DraftStoreResult.SourceChanged
        if (current.staffPaused) return DraftStoreResult.StaffPaused
        if (current.eventId != write.key.eventId || current.threadId != write.threadId ||
            current.contactId != write.contactId || current.recipient != write.recipient ||
            current.revision != write.expectedRevision
        ) return DraftStoreResult.SourceChanged
        receipts[write.key]?.let { return DraftStoreResult.Held(it, DraftDisposition.ALREADY_HELD) }
        val receipt = receiptFor(write)
        receipts[write.key] = receipt
        inserts++
        if (throwAfterCommit) throw IllegalStateException("Simulated lost commit acknowledgement")
        return DraftStoreResult.Held(receiptTransform(receipt), DraftDisposition.CREATED)
    }
}

internal fun receiptFor(write: HeldDraftWrite) = HeldDraftReceipt(
    "draft-1", write.key, write.threadId, write.contactId, write.recipient, write.expectedRevision, write.body,
)
