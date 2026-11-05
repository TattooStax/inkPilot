package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.BoundedIntentReplyRenderer
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftRendererPolicy
import inkpilot.agent.DraftSource
import inkpilot.agent.LeadQuestion
import java.util.UUID

private const val EXISTING_RENDERER_COLUMN = 3
private const val EXISTING_STAFF_POLICY_COLUMN = 4

private data class ExistingQuestion(val id: String, val body: String, val state: String, val validPolicy: Boolean)

private fun ExistingQuestion?.replay(body: String): StaffQuestionResult? {
    if (this == null) return null
    if (!validPolicy) return StaffQuestionResult.Stale
    return when (state) {
        "HELD" -> if (this.body == body) StaffQuestionResult.Held(id) else StaffQuestionResult.ConflictingHeld
        "DISMISSED" -> null
        else -> StaffQuestionResult.Stale
    }
}

fun StudioRepository.staffQuestionTarget(threadId: Long): LeadIntakeTarget? =
    database.studioStaffQuestionTarget(threadId)

fun StudioRepository.holdStaffQuestion(request: DraftRequest, question: LeadQuestion): StaffQuestionResult =
    database.holdStudioStaffQuestion(request, question)

internal fun SQLiteDatabase.studioStaffQuestionTarget(threadId: Long): LeadIntakeTarget? = transaction {
    rawQuery("""SELECT e.event_id, c.display_name FROM inbound_events e
        JOIN studio_contacts c ON c.id = e.contact_id
        WHERE e.thread_id = ?
        ORDER BY e.rowid DESC LIMIT 1""", arrayOf(threadId.toString())).use { cursor ->
        if (!cursor.moveToFirst()) return@transaction null
        val eventId = cursor.getString(0)
        val name = cursor.getString(1)
        val source = studioSource(eventId) ?: return@transaction null
        if (staffHandledSource(eventId) || studioContact(source.contactId)?.paused != false) return@transaction null
        if (hasStaffQuestionClaim(eventId)) return@transaction null
        LeadIntakeTarget(
            DraftRequest(eventId, source.threadId, source.contactId, source.revision), name,
            source.recipient, source.inboundText, studioLeadIntake(source), hasStoredLeadIntake(eventId),
        )
    }
}

private fun SQLiteDatabase.hasStaffQuestionClaim(eventId: String): Boolean = rawQuery(
    """SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id=s.draft_id WHERE h.event_id=?""",
    arrayOf(eventId),
).use { it.moveToFirst() }

private fun SQLiteDatabase.currentStaffQuestionSource(request: DraftRequest): DraftSource? {
    val source = studioSource(request.eventId) ?: return null
    if (source.threadId != request.threadId || source.contactId != request.contactId ||
        source.revision != request.expectedRevision) return null
    if (studioContact(source.contactId)?.paused != false || staffHandledSource(source.eventId)) return null
    return source
}

private fun SQLiteDatabase.hasCompetingQuestion(source: DraftSource): Boolean = rawQuery(
    """SELECT 1 FROM held_drafts d WHERE d.event_id=? AND d.source_revision=? AND d.state='HELD'
       AND d.kind <> 'STAFF_QUESTION' AND ${StudioDraftPolicySchema.REVIEWABLE}""",
    arrayOf(source.eventId, source.revision.toString()),
).use { it.moveToFirst() }

private fun SQLiteDatabase.existingQuestion(source: DraftSource): ExistingQuestion? = rawQuery(
    """SELECT draft_id,body,state,renderer_policy,staff_question_policy FROM held_drafts
       WHERE event_id=? AND kind='STAFF_QUESTION' AND source_revision=?""",
    arrayOf(source.eventId, source.revision.toString()),
).use { cursor ->
    if (!cursor.moveToFirst()) null else ExistingQuestion(
        cursor.getString(0), cursor.getString(1), cursor.getString(2),
        cursor.getString(EXISTING_RENDERER_COLUMN) == "LEGACY_RAW" &&
            cursor.getString(EXISTING_STAFF_POLICY_COLUMN) == DraftRendererPolicy.STAFF_QUESTION_V1.name,
    )
}

private fun SQLiteDatabase.advanceDismissedQuestion(source: DraftSource): Long? {
    val next = source.revision + 1
    if (update("studio_contacts", ContentValues().apply { put("revision", next) },
            "id=? AND revision=? AND latest_event_id=?",
            arrayOf(source.contactId, source.revision.toString(), source.eventId)) != 1) return null
    check(update("lead_intake_reviews", ContentValues().apply { put("source_revision", next) },
        "event_id=? AND source_revision=? AND contact_id=? AND thread_id=?",
        arrayOf(source.eventId, source.revision.toString(), source.contactId, source.threadId)) == 1)
    return next
}

internal fun SQLiteDatabase.holdStudioStaffQuestion(
    request: DraftRequest, question: LeadQuestion,
): StaffQuestionResult = transaction {
    val source = currentStaffQuestionSource(request) ?: return@transaction StaffQuestionResult.Stale
    val review = studioLeadIntake(source) ?: return@transaction StaffQuestionResult.Stale
    if (question !in review.missingQuestions) return@transaction StaffQuestionResult.Stale
    val body = BoundedIntentReplyRenderer.staffQuestion(review.language, question)
    if (hasStaffQuestionClaim(source.eventId) || hasCompetingQuestion(source))
        return@transaction StaffQuestionResult.Stale
    val prior = existingQuestion(source)
    prior.replay(body)?.let { return@transaction it }
    // A deliberate dismissal permits a new generation, retaining the old row as history.
    val revision = if (prior == null) source.revision else
        (advanceDismissedQuestion(source) ?: return@transaction StaffQuestionResult.Stale)
    val id = UUID.randomUUID().toString()
    insertOrThrow("held_drafts", null, ContentValues().apply {
        put("draft_id", id); put("event_id", source.eventId); put("kind", "STAFF_QUESTION")
        put("thread_id", source.threadId); put("contact_id", source.contactId)
        put("recipient", source.recipient); put("source_revision", revision)
        put("body", body); put("staff_question_policy", DraftRendererPolicy.STAFF_QUESTION_V1.name)
        put("created_at", System.currentTimeMillis())
    })
    resolveStaffAttentionForEvent(source.eventId, revision)
    StaffQuestionResult.Held(id)
}
