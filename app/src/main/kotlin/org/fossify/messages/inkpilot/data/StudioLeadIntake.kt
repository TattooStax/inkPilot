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
    val hasStoredReview: Boolean,
)

sealed interface LeadIntakeReviewResult {
    data class Saved(val request: DraftRequest) : LeadIntakeReviewResult
    data object Stale : LeadIntakeReviewResult
}

private const val DEFAULT_TARGETS = 20
private const val MAX_TARGETS = 100
private const val STAFF_UI_SOURCE = "STAFF_UI"
private const val INSERT_FAILED = -1L

private object ReviewColumns {
    const val REVISION = 0
    const val CONTACT = 1
    const val THREAD = 2
    const val LANGUAGE = 3
    const val MASK = 4
    const val SOURCE = 5
}

private object TargetColumns {
    const val EVENT = 0
    const val THREAD = 1
    const val CONTACT = 2
    const val RECIPIENT = 3
    const val BODY = 4
    const val DISPLAY_NAME = 5
    const val REVISION = 6
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

fun StudioRepository.leadIntakeTargets(limit: Int = DEFAULT_TARGETS): List<LeadIntakeTarget> =
    database.studioLeadIntakeTargets(limit)

fun StudioRepository.reviewLeadIntake(request: DraftRequest, language: ReplyLanguage,
                                      missing: Set<LeadQuestion>): LeadIntakeReviewResult =
    database.saveStudioLeadIntake(request, language, missing)

fun StudioRepository.clearLeadIntake(request: DraftRequest): LeadIntakeReviewResult =
    database.clearStudioLeadIntake(request)

internal fun SQLiteDatabase.hasStoredLeadIntake(eventId: String): Boolean =
    rawQuery("SELECT 1 FROM lead_intake_reviews WHERE event_id = ?", arrayOf(eventId)).use {
        it.moveToFirst()
    }

/** A corrupt, stale, or cross-contact row cannot become model authority. */
internal fun SQLiteDatabase.studioLeadIntake(source: DraftSource): ReviewedLeadIntake? = rawQuery(
    """SELECT source_revision, contact_id, thread_id, language, missing_mask, source
       FROM lead_intake_reviews WHERE event_id = ?""", arrayOf(source.eventId),
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    val revision = cursor.getLong(ReviewColumns.REVISION)
    val contactId = cursor.getString(ReviewColumns.CONTACT)
    val threadId = cursor.getString(ReviewColumns.THREAD)
    val language = ReplyLanguage.entries.firstOrNull { it.name == cursor.getString(ReviewColumns.LANGUAGE) }
    val mask = cursor.getInt(ReviewColumns.MASK)
    if (revision != source.revision || contactId != source.contactId || threadId != source.threadId) return@use null
    if (language == null || mask !in 1..LeadQuestionMask.MAX_VALUE ||
        cursor.getString(ReviewColumns.SOURCE) != STAFF_UI_SOURCE) return@use null
    ReviewedLeadIntake(source.eventId, revision, contactId, threadId, language, LeadQuestionMask.decode(mask))
}

private fun SQLiteDatabase.bumpReviewRevision(source: DraftSource, nextRevision: Long): Boolean =
    update("studio_contacts", ContentValues().apply { put("revision", nextRevision) },
        "id = ? AND revision = ? AND latest_event_id = ?",
        arrayOf(source.contactId, source.revision.toString(), source.eventId)) == 1

internal fun SQLiteDatabase.studioLeadIntakeTargets(limit: Int): List<LeadIntakeTarget> {
    require(limit in 1..MAX_TARGETS)
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
            val targets = mutableListOf<LeadIntakeTarget>()
            while (cursor.moveToNext()) {
                val source = studioSource(cursor.getString(TargetColumns.EVENT)) ?: continue
                val identityMatches = source.threadId == cursor.getString(TargetColumns.THREAD) &&
                    source.contactId == cursor.getString(TargetColumns.CONTACT)
                val messageMatches = source.recipient == cursor.getString(TargetColumns.RECIPIENT) &&
                    source.revision == cursor.getLong(TargetColumns.REVISION)
                if (identityMatches && messageMatches) {
                    targets.add(LeadIntakeTarget(
                        DraftRequest(source.eventId, source.threadId, source.contactId, source.revision),
                        cursor.getString(TargetColumns.DISPLAY_NAME), source.recipient,
                        cursor.getString(TargetColumns.BODY), studioLeadIntake(source),
                        hasStoredLeadIntake(source.eventId),
                    ))
                }
            }
            targets
        }
    }
}

private fun SQLiteDatabase.currentReviewSource(request: DraftRequest): DraftSource? {
    val source = studioSource(request.eventId) ?: return null
    if (source.threadId != request.threadId || source.contactId != request.contactId) return null
    if (source.revision != request.expectedRevision || staffHandledSource(request.eventId)) return null
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
        if (old?.language == language && old.missingQuestions == missing)
            return@transaction LeadIntakeReviewResult.Saved(request)
        val nextRevision = source.revision + 1
        if (!bumpReviewRevision(source, nextRevision)) return@transaction LeadIntakeReviewResult.Stale
        check(studioSource(source.eventId)?.revision == nextRevision)
        check(insertWithOnConflict("lead_intake_reviews", null, ContentValues().apply {
            put("event_id", source.eventId); put("source_revision", nextRevision)
            put("contact_id", source.contactId); put("thread_id", source.threadId)
            put("language", language.name); put("missing_mask", LeadQuestionMask.encode(missing))
            put("reviewed_at", System.currentTimeMillis()); put("source", STAFF_UI_SOURCE)
        }, SQLiteDatabase.CONFLICT_REPLACE) != INSERT_FAILED)
        // A scope edit must never authorize RAW regeneration while the selector is disabled.
        LeadIntakeReviewResult.Saved(request.copy(expectedRevision = nextRevision))
    }
}

internal fun SQLiteDatabase.clearStudioLeadIntake(request: DraftRequest): LeadIntakeReviewResult = transaction {
    val source = currentReviewSource(request) ?: return@transaction LeadIntakeReviewResult.Stale
    // A stale or tampered row is invisible as evidence but still belongs to this event.
    if (!hasStoredLeadIntake(source.eventId)) return@transaction LeadIntakeReviewResult.Saved(request)
    val nextRevision = source.revision + 1
    if (!bumpReviewRevision(source, nextRevision)) return@transaction LeadIntakeReviewResult.Stale
    check(delete("lead_intake_reviews", "event_id = ?", arrayOf(source.eventId)) == 1)
    delete("draft_regenerations", "event_id = ?", arrayOf(source.eventId))
    LeadIntakeReviewResult.Saved(request.copy(expectedRevision = nextRevision))
}
