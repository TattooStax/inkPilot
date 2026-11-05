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
    val reviewedLeadIntake: ReviewedLeadIntake? = null,
)

/** Host-issued permission to ask only these missing lead questions for this source revision. */
data class ReviewedLeadIntake(
    val sourceId: String,
    val sourceRevision: Long,
    val contactId: String,
    val threadId: String,
    val language: ReplyLanguage,
    val missingQuestions: Set<LeadQuestion>,
)

enum class ReplyLanguage { EN, ES, FR }
enum class LeadQuestion { PLACEMENT, SIZE, STYLE, REFERENCE, ARTIST, DATE }

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

    /** A closed renderer can reject missing host authority before model execution. */
    fun eligible(evidence: DraftEvidence?): Boolean = true

    /** Constrain the model to the host-approved choices, without free-text facts. */
    fun instructions(evidence: DraftEvidence?): String? = null

    /** Older receipts lack a recorded renderer identity; closed routes must not adopt them. */
    fun acceptsUnprovenReceipt(): Boolean = false

    companion object {
        val RAW = object : DraftReplyRenderer {
            override fun render(modelOutput: String, evidence: DraftEvidence?): String = modelOutput
            override fun acceptsUnprovenReceipt(): Boolean = true
        }
    }
}
