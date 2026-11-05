package inkpilot.agent

/**
 * One host-issued, revisioned view for a draft attempt. The host reads this as a
 * consistent snapshot; only it may supply verified facts. Free-text notes are
 * context for selecting a question, never authority for rendered claims.
 */
data class DraftEvidence(
    val request: DraftRequest,
    val source: DraftSource,
    val reviewedMemory: List<ContactMemory>,
    val studioGuide: String = "",
    val verifiedFacts: List<VerifiedDraftFact> = emptyList(),
)

sealed interface VerifiedDraftFact {
    val sourceId: String
    val sourceRevision: Long
    val contactId: String
    val threadId: String

    data class Price(
        override val sourceId: String,
        override val sourceRevision: Long,
        override val contactId: String,
        override val threadId: String,
        val cents: Long,
    ) : VerifiedDraftFact

    data class Appointment(
        override val sourceId: String,
        override val sourceRevision: Long,
        override val contactId: String,
        override val threadId: String,
        val startsAt: Long,
        val zoneId: String,
    ) : VerifiedDraftFact
}

fun interface DraftEvidenceReader {
    fun readEvidence(request: DraftRequest): DraftEvidence?
}

/** Null means stop before persistence and create a staff-attention item. */
fun interface DraftReplyRenderer {
    fun render(modelOutput: String, evidence: DraftEvidence?): String?

    companion object {
        val RAW = DraftReplyRenderer { text, _ -> text }
    }
}
