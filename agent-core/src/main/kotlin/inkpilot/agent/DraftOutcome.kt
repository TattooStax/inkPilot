package inkpilot.agent

enum class DraftStopReason {
    SOURCE_MISSING,
    SCOPE_MISMATCH,
    SOURCE_CHANGED,
    STAFF_PAUSED,
    CANCELLED,
    INVALID_CONTEXT,
    CONTEXT_LIMIT,
    EMPTY_REPLY,
    REPLY_LIMIT,
    UNSAFE_CONTENT,
    INVALID_RECEIPT,
}

enum class DraftFailureStage { SOURCE_READ, RECEIPT_READ, MEMORY_READ, MODEL }

sealed interface DraftOutcome {
    data class Held(val receipt: HeldDraftReceipt, val disposition: DraftDisposition) : DraftOutcome
    data class Stopped(val reason: DraftStopReason) : DraftOutcome
    data class Failed(val stage: DraftFailureStage) : DraftOutcome

    /** A write was attempted but no valid committed receipt was obtained. Retry this key. */
    data class PersistenceUnconfirmed(val key: DraftKey) : DraftOutcome
}
