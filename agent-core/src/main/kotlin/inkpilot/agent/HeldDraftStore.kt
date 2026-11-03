package inkpilot.agent

enum class DraftKind { CUSTOMER_SERVICE_REPLY }

data class DraftKey(val eventId: String, val kind: DraftKind = DraftKind.CUSTOMER_SERVICE_REPLY)

data class HeldDraftWrite(
    val key: DraftKey,
    val threadId: String,
    val contactId: String,
    val recipient: String,
    val expectedRevision: Long,
    val body: String,
)

data class HeldDraftReceipt(
    val draftId: String,
    val key: DraftKey,
    val threadId: String,
    val contactId: String,
    val recipient: String,
    val sourceRevision: Long,
    val body: String,
)

enum class DraftDisposition { CREATED, ALREADY_HELD }

sealed interface DraftStoreResult {
    data class Held(val receipt: HeldDraftReceipt, val disposition: DraftDisposition) : DraftStoreResult
    data object SourceChanged : DraftStoreResult
    data object StaffPaused : DraftStoreResult
}

/** The existing host repository remains the sole owner of customer and draft records. */
interface HeldDraftStore {
    fun readSource(eventId: String): DraftSource?
    /** Historical receipt lookup only; this does not attest to current thread suitability. */
    fun findHeldDraft(key: DraftKey): HeldDraftReceipt?

    /**
     * In ONE durable transaction:
     * - recheck event/thread/contact/recipient binding, current revision, and staff pause;
     * - reject deleted/reassigned/changed sources or paused threads;
     * - enforce a UNIQUE key on (eventId, kind), returning the original receipt on replay;
     * - otherwise insert a held-only draft and return its committed receipt.
     *
     * Editing the source, changing memory, cancelling a pending run, or changing recipient
     * routing must advance the host revision. Pause changes must also advance it, so a
     * pause/resume cycle cannot resurrect an old run. The key must survive process restarts
     * and consumed/deleted drafts (a receipt/tombstone prevents replay from recreating them).
     * No implementation may send SMS here. An exception can mean commit status is unknown.
     */
    fun holdIfCurrent(write: HeldDraftWrite): DraftStoreResult
}
