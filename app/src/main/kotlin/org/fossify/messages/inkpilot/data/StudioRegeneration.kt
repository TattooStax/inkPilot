package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.DraftRequest

sealed interface DraftRegenerationOutcome {
    /** Authorized for local generation, still held until staff separately approves sending. */
    data class Held(val request: DraftRequest) : DraftRegenerationOutcome
    data object Stale : DraftRegenerationOutcome
    data object AlreadyClaimed : DraftRegenerationOutcome
    data object ReviewScopeUnavailable : DraftRegenerationOutcome
}

internal fun SQLiteDatabase.regenerateStudioDraft(
    eventId: String, expectedRevision: Long, allowStoredLeadReview: Boolean,
): DraftRegenerationOutcome =
    transaction {
        // A send claim is an immutable event-level tombstone, including FAILED and UNKNOWN.
        rawQuery(
            "SELECT 1 FROM send_claims s JOIN held_drafts d ON d.draft_id = s.draft_id WHERE d.event_id = ?",
            arrayOf(eventId),
        ).use {
            if (it.moveToFirst()) return@transaction DraftRegenerationOutcome.AlreadyClaimed
        }
        val source = studioSource(eventId) ?: return@transaction DraftRegenerationOutcome.Stale
        if (source.staffPaused || source.revision != expectedRevision || staffHandledSource(eventId)) {
            return@transaction DraftRegenerationOutcome.Stale
        }
        if (!allowStoredLeadReview && hasStoredLeadIntake(eventId)) {
            return@transaction DraftRegenerationOutcome.ReviewScopeUnavailable
        }
        insertWithOnConflict("draft_regenerations", null, ContentValues().apply {
            put("event_id", eventId); put("source_revision", expectedRevision)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        delete("draft_attempts", "event_id = ? AND source_revision = ?", arrayOf(eventId, expectedRevision.toString()))
        DraftRegenerationOutcome.Held(DraftRequest(eventId, source.threadId, source.contactId, expectedRevision))
    }
