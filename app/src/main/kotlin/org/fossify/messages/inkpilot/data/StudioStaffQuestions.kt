package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.BoundedIntentReplyRenderer
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftRendererPolicy
import inkpilot.agent.LeadQuestion
import java.util.UUID

/** A deterministic staff choice. A held row is never a send authorization. */
sealed interface StaffQuestionResult {
    data class Held(val draftId: String) : StaffQuestionResult
    data object Stale : StaffQuestionResult
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
        if (staffHandledSource(eventId) || studioContact(source.contactId)?.paused != false ||
            rawQuery("""SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id = s.draft_id
                WHERE h.event_id = ?""", arrayOf(eventId)).use { it.moveToFirst() }) return@transaction null
        LeadIntakeTarget(
            DraftRequest(eventId, source.threadId, source.contactId, source.revision), name,
            source.recipient, source.inboundText, studioLeadIntake(source), hasStoredLeadIntake(eventId),
        )
    }
}

internal fun SQLiteDatabase.holdStudioStaffQuestion(
    request: DraftRequest, question: LeadQuestion,
): StaffQuestionResult = transaction {
    val source = studioSource(request.eventId) ?: return@transaction StaffQuestionResult.Stale
    if (source.threadId != request.threadId || source.contactId != request.contactId ||
        source.revision != request.expectedRevision || studioContact(source.contactId)?.paused != false ||
        staffHandledSource(source.eventId)) return@transaction StaffQuestionResult.Stale
    val review = studioLeadIntake(source) ?: return@transaction StaffQuestionResult.Stale
    if (question !in review.missingQuestions) return@transaction StaffQuestionResult.Stale
    val body = BoundedIntentReplyRenderer.staffQuestion(review.language, question)
    if (rawQuery("""SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id = s.draft_id
        WHERE h.event_id = ?""", arrayOf(source.eventId)).use { it.moveToFirst() })
        return@transaction StaffQuestionResult.Stale
    rawQuery("""SELECT draft_id,body,state FROM held_drafts WHERE event_id = ?
        AND kind = 'STAFF_QUESTION' AND source_revision = ?""",
        arrayOf(source.eventId, source.revision.toString())).use { existing ->
        if (existing.moveToFirst()) return@transaction if (existing.getString(1) == body &&
            existing.getString(2) == "HELD") StaffQuestionResult.Held(existing.getString(0))
        else StaffQuestionResult.Stale
    }
    if (rawQuery("""SELECT 1 FROM held_drafts d WHERE d.event_id = ? AND d.state = 'HELD'
        AND ${StudioDraftPolicySchema.REVIEWABLE}""", arrayOf(source.eventId)).use { it.moveToFirst() })
        return@transaction StaffQuestionResult.Stale
    val id = UUID.randomUUID().toString()
    insertOrThrow("held_drafts", null, ContentValues().apply {
        put("draft_id", id); put("event_id", source.eventId); put("kind", "STAFF_QUESTION")
        put("thread_id", source.threadId); put("contact_id", source.contactId)
        put("recipient", source.recipient); put("source_revision", source.revision)
        put("body", body); put("staff_question_policy", DraftRendererPolicy.STAFF_QUESTION_V1.name)
        put("created_at", System.currentTimeMillis())
    })
    resolveStaffAttentionForEvent(source.eventId, source.revision)
    StaffQuestionResult.Held(id)
}
