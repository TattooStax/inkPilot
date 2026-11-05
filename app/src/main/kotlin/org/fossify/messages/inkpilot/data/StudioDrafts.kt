package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.DraftDisposition
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftRendererPolicy
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.DraftSource
import inkpilot.agent.HeldDraftReceipt
import inkpilot.agent.HeldDraftWrite
import java.util.UUID

private const val MAX_STUDIO_DRAFT_LENGTH = 800
private const val RECEIPT_POLICY_COLUMN = 6
private const val STAFF_EDIT_COLUMN = 14
private const val RENDERER_POLICY_COLUMN = 15
private const val REVIEW_SUBSCRIPTION_COLUMN = 9
private const val REVIEW_CREATED_COLUMN = 10
private const val REVIEW_ALLOWED_COLUMN = 11
private const val REVIEW_STATE_COLUMN = 12
private const val REVIEW_CONTACT_COLUMN = 3
private const val REVIEW_RECIPIENT_COLUMN = 4
private const val REVIEW_NAME_COLUMN = 5
private const val REVIEW_INBOUND_COLUMN = 6
private const val REVIEW_BODY_COLUMN = 7
private const val REVIEW_REVISION_COLUMN = 8
private const val REVIEW_SEND_BODY_COLUMN = 13

internal fun SQLiteDatabase.studioReceipt(key: DraftKey): HeldDraftReceipt? = rawQuery(
    """SELECT draft_id, thread_id, contact_id, recipient, source_revision, body, renderer_policy
        FROM held_drafts WHERE event_id = ? AND kind = ?
        AND source_revision = (SELECT revision FROM studio_contacts WHERE id = held_drafts.contact_id)""",
    arrayOf(key.eventId, key.kind.name),
).use {
    if (!it.moveToFirst()) null else HeldDraftReceipt(
        it.getString(0), key, it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getString(5),
        DraftRendererPolicy.valueOf(it.getString(RECEIPT_POLICY_COLUMN)),
    )
}

private fun SQLiteDatabase.unhandledSource(write: HeldDraftWrite): DraftSource? {
    val source = studioSource(write.key.eventId) ?: return null
    return source.takeUnless { staffHandledSource(write.key.eventId) }
}

internal fun SQLiteDatabase.holdStudioDraft(write: HeldDraftWrite): DraftStoreResult {
    require(write.body.isNotBlank() && write.body.length <= MAX_STUDIO_DRAFT_LENGTH) { "Invalid draft text" }
    return transaction {
        val source = unhandledSource(write) ?: return@transaction DraftStoreResult.SourceChanged
        if (source.staffPaused) return@transaction DraftStoreResult.StaffPaused
        if (source.threadId != write.threadId || source.contactId != write.contactId ||
            source.recipient != write.recipient || source.revision != write.expectedRevision
        ) return@transaction DraftStoreResult.SourceChanged
        studioReceipt(write.key)?.let {
            return@transaction if (it.sourceRevision == source.revision) {
                DraftStoreResult.Held(it, DraftDisposition.ALREADY_HELD)
            } else DraftStoreResult.SourceChanged
        }
        if (rawQuery("SELECT 1 FROM send_claims s JOIN held_drafts d ON d.draft_id = s.draft_id WHERE d.event_id = ?", arrayOf(write.key.eventId)).use { it.moveToFirst() }) return@transaction DraftStoreResult.SourceChanged
        if (rawQuery("""SELECT 1 FROM held_drafts WHERE event_id=?
            AND kind IN ('PROPOSAL_REPLY','STAFF_QUESTION') AND state='HELD'""",
                arrayOf(write.key.eventId)).use { it.moveToFirst() }) return@transaction DraftStoreResult.SourceChanged
        val historyNeedsAuthorization = rawQuery(
            "SELECT 1 FROM held_drafts WHERE event_id = ? AND kind = ? AND NOT EXISTS (SELECT 1 FROM draft_regenerations WHERE event_id = ? AND source_revision = ?)",
            arrayOf(write.key.eventId, write.key.kind.name, write.key.eventId, write.expectedRevision.toString()),
        ).use { it.moveToFirst() }
        if (historyNeedsAuthorization) return@transaction DraftStoreResult.SourceChanged
        val id = UUID.randomUUID().toString()
        insertOrThrow("held_drafts", null, ContentValues().apply {
            put("draft_id", id); put("event_id", write.key.eventId); put("kind", write.key.kind.name)
            put("thread_id", write.threadId); put("contact_id", write.contactId); put("recipient", write.recipient)
            put("source_revision", write.expectedRevision); put("body", write.body); put("created_at", System.currentTimeMillis())
            put("renderer_policy", write.rendererPolicy.name)
        })
        resolveStaffAttentionForEvent(write.key.eventId, write.expectedRevision)
        DraftStoreResult.Held(checkNotNull(studioReceipt(write.key)), DraftDisposition.CREATED)
    }
}

internal fun SQLiteDatabase.reviewStudioDrafts(heldOnly: Boolean = true, limit: Int = 100, threadId: Long? = null, draftId: String? = null): List<ReviewDraft> {
    require(limit in 1..500)
    val condition = if (heldOnly) "d.state = 'HELD'" else "d.state <> 'DISMISSED'"
    val filter = when { draftId != null -> " AND d.draft_id = ?"; threadId != null -> " AND d.thread_id = ?"; else -> "" }
    val args = buildList { draftId?.let { add(it) } ?: threadId?.let { add(it.toString()) }; add(limit.toString()) }
    return rawQuery(
        """
            SELECT d.draft_id, d.event_id, d.thread_id, d.contact_id, d.recipient, c.display_name,
                   e.body, d.body, d.source_revision, e.subscription_id, d.created_at,
                   (d.state = 'HELD' AND ${StudioDraftPolicySchema.REVIEWABLE} AND c.paused = 0
                    AND c.revision = d.source_revision AND c.latest_event_id = d.event_id
                    AND (d.staff_question_policy = 'STAFF_QUESTION_V1' OR
                         (SELECT drafting_enabled FROM agent_settings WHERE id = 1) = 1)),
                   d.state, s.body, d.staff_edit_body, ${StudioDraftPolicySchema.EFFECTIVE_POLICY}
            FROM held_drafts d JOIN studio_contacts c ON c.id = d.contact_id
            JOIN inbound_events e ON e.event_id = d.event_id LEFT JOIN send_claims s ON s.draft_id = d.draft_id
            WHERE $condition$filter ORDER BY d.created_at DESC, d.draft_id LIMIT ?
        """.trimIndent(), args.toTypedArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val policy = DraftRendererPolicy.valueOf(cursor.getString(RENDERER_POLICY_COLUMN))
                val canReview = cursor.getInt(REVIEW_ALLOWED_COLUMN) != 0 &&
                    (policy != DraftRendererPolicy.STAFF_QUESTION_V1 || !staffHandledSource(cursor.getString(1)))
                add(ReviewDraft(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2).toLong(),
                    cursor.getString(REVIEW_CONTACT_COLUMN), cursor.getString(REVIEW_RECIPIENT_COLUMN),
                    cursor.getString(REVIEW_NAME_COLUMN), cursor.getString(REVIEW_INBOUND_COLUMN),
                    cursor.getString(REVIEW_BODY_COLUMN), cursor.getLong(REVIEW_REVISION_COLUMN),
                    cursor.getInt(REVIEW_SUBSCRIPTION_COLUMN), cursor.getLong(REVIEW_CREATED_COLUMN),
                    canReview, cursor.getString(REVIEW_STATE_COLUMN),
                    if (cursor.isNull(REVIEW_SEND_BODY_COLUMN)) null else cursor.getString(REVIEW_SEND_BODY_COLUMN),
                    if (cursor.isNull(STAFF_EDIT_COLUMN)) null else cursor.getString(STAFF_EDIT_COLUMN),
                    policy,
                ))
            }
        }
    }
}

internal fun SQLiteDatabase.dismissStudioDraft(draftId: String): Boolean =
    update("held_drafts", ContentValues().apply {
        put("state", "DISMISSED")
        putNull("staff_edit_body")
    }, "draft_id = ? AND state = 'HELD'", arrayOf(draftId)) == 1
