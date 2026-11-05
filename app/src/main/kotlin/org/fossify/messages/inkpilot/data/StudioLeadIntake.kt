package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftSource
import inkpilot.agent.LeadQuestion
import inkpilot.agent.ReplyLanguage
import inkpilot.agent.ReviewedLeadIntake

/** A local staff choice for one current message. It never approves or sends a reply. */
data class LeadIntakeTarget(
    val request: DraftRequest,
    val displayName: String,
    val recipient: String,
    val inboundText: String,
    val reviewed: ReviewedLeadIntake?,
)

sealed interface LeadIntakeReviewResult {
    data class Saved(val request: DraftRequest) : LeadIntakeReviewResult
    data object Stale : LeadIntakeReviewResult
}

internal object StudioLeadIntakeSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS lead_intake_reviews (
                event_id TEXT PRIMARY KEY NOT NULL REFERENCES inbound_events(event_id),
                source_revision INTEGER NOT NULL CHECK(source_revision >= 0),
                contact_id TEXT NOT NULL REFERENCES studio_contacts(id),
                thread_id TEXT NOT NULL,
                language TEXT NOT NULL CHECK(language IN ('EN','ES','FR')),
                missing_mask INTEGER NOT NULL CHECK(missing_mask BETWEEN 1 AND 63),
                reviewed_at INTEGER NOT NULL CHECK(reviewed_at >= 0),
                source TEXT NOT NULL CHECK(source = 'STAFF_UI')
            )
        """.trimIndent())
    }
}

fun StudioRepository.leadIntakeTargets(limit: Int = 20): List<LeadIntakeTarget> = database.studioLeadIntakeTargets(limit)

fun StudioRepository.reviewLeadIntake(request: DraftRequest, language: ReplyLanguage,
                                      missing: Set<LeadQuestion>): LeadIntakeReviewResult =
    database.saveStudioLeadIntake(request, language, missing)

fun StudioRepository.clearLeadIntake(request: DraftRequest): LeadIntakeReviewResult =
    database.clearStudioLeadIntake(request)

/** A corrupt, stale, or cross-contact row cannot become model authority. */
internal fun SQLiteDatabase.studioLeadIntake(source: DraftSource): ReviewedLeadIntake? = rawQuery(
    """SELECT source_revision, contact_id, thread_id, language, missing_mask, source
       FROM lead_intake_reviews WHERE event_id = ?""", arrayOf(source.eventId),
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    val revision = cursor.getLong(0)
    val contactId = cursor.getString(1)
    val threadId = cursor.getString(2)
    val language = ReplyLanguage.entries.firstOrNull { it.name == cursor.getString(3) }
    val mask = cursor.getInt(4)
    if (revision != source.revision || contactId != source.contactId || threadId != source.threadId ||
        language == null || mask !in 1..63 || cursor.getString(5) != "STAFF_UI") return@use null
    ReviewedLeadIntake(source.eventId, revision, contactId, threadId, language, questions(mask))
}

/** Bit positions are persisted schema and must not depend on enum declaration order. */
private fun bit(question: LeadQuestion): Int = when (question) {
    LeadQuestion.PLACEMENT -> 1
    LeadQuestion.SIZE -> 2
    LeadQuestion.STYLE -> 4
    LeadQuestion.REFERENCE -> 8
    LeadQuestion.ARTIST -> 16
    LeadQuestion.DATE -> 32
}
private fun maskOf(questions: Set<LeadQuestion>): Int = questions.fold(0) { mask, value -> mask or bit(value) }
private fun questions(mask: Int): Set<LeadQuestion> = LeadQuestion.entries.filterTo(mutableSetOf()) {
    mask and bit(it) != 0
}

private fun SQLiteDatabase.bumpReviewRevision(source: DraftSource, nextRevision: Long): Boolean =
    update("studio_contacts", ContentValues().apply { put("revision", nextRevision) },
        "id = ? AND revision = ? AND latest_event_id = ?",
        arrayOf(source.contactId, source.revision.toString(), source.eventId)) == 1

internal fun SQLiteDatabase.studioLeadIntakeTargets(limit: Int): List<LeadIntakeTarget> {
    require(limit in 1..100)
    return transaction {
        rawQuery("""
            SELECT e.event_id, e.thread_id, e.contact_id, e.recipient, e.body, c.display_name, c.revision
            FROM studio_contacts c JOIN inbound_events e ON e.event_id = c.latest_event_id
            WHERE NOT EXISTS (SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id = s.draft_id
                              WHERE h.event_id = e.event_id)
              AND NOT EXISTS (SELECT 1 FROM staff_attention a WHERE a.event_id = e.event_id
                              AND a.resolution = 'STAFF_HANDLED')
            ORDER BY e.rowid DESC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val source = studioSource(cursor.getString(0)) ?: continue
                    if (source.threadId != cursor.getString(1) || source.contactId != cursor.getString(2) ||
                        source.recipient != cursor.getString(3) || source.revision != cursor.getLong(6)) continue
                    add(LeadIntakeTarget(DraftRequest(source.eventId, source.threadId, source.contactId, source.revision),
                        cursor.getString(5), source.recipient, cursor.getString(4), studioLeadIntake(source)))
                }
            }
        }
    }
}

private fun SQLiteDatabase.currentReviewSource(request: DraftRequest): DraftSource? {
    val source = studioSource(request.eventId) ?: return null
    if (source.threadId != request.threadId || source.contactId != request.contactId ||
        source.revision != request.expectedRevision || staffHandledSource(request.eventId)) return null
    rawQuery("SELECT 1 FROM send_claims s JOIN held_drafts h ON h.draft_id = s.draft_id WHERE h.event_id = ?",
        arrayOf(request.eventId)).use { if (it.moveToFirst()) return null }
    return source
}

internal fun SQLiteDatabase.saveStudioLeadIntake(request: DraftRequest, language: ReplyLanguage,
                                                  missing: Set<LeadQuestion>): LeadIntakeReviewResult {
    require(missing.isNotEmpty()) { "Select at least one missing detail" }
    return transaction {
        val source = currentReviewSource(request) ?: return@transaction LeadIntakeReviewResult.Stale
        val old = studioLeadIntake(source)
        if (old?.language == language && old.missingQuestions == missing) return@transaction LeadIntakeReviewResult.Saved(request)
        val nextRevision = source.revision + 1
        if (!bumpReviewRevision(source, nextRevision)) return@transaction LeadIntakeReviewResult.Stale
        check(studioSource(source.eventId)?.revision == nextRevision)
        check(insertWithOnConflict("lead_intake_reviews", null, ContentValues().apply {
            put("event_id", source.eventId); put("source_revision", nextRevision)
            put("contact_id", source.contactId); put("thread_id", source.threadId)
            put("language", language.name); put("missing_mask", maskOf(missing))
            put("reviewed_at", System.currentTimeMillis()); put("source", "STAFF_UI")
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        // A staff review authorizes a new held draft after a previous stale receipt.
        check(insertWithOnConflict("draft_regenerations", null, ContentValues().apply {
            put("event_id", source.eventId); put("source_revision", nextRevision)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        LeadIntakeReviewResult.Saved(request.copy(expectedRevision = nextRevision))
    }
}

internal fun SQLiteDatabase.clearStudioLeadIntake(request: DraftRequest): LeadIntakeReviewResult = transaction {
    val source = currentReviewSource(request) ?: return@transaction LeadIntakeReviewResult.Stale
    if (studioLeadIntake(source) == null) return@transaction LeadIntakeReviewResult.Saved(request)
    val nextRevision = source.revision + 1
    if (!bumpReviewRevision(source, nextRevision)) return@transaction LeadIntakeReviewResult.Stale
    check(delete("lead_intake_reviews", "event_id = ?", arrayOf(source.eventId)) == 1)
    delete("draft_regenerations", "event_id = ?", arrayOf(source.eventId))
    LeadIntakeReviewResult.Saved(request.copy(expectedRevision = nextRevision))
}
