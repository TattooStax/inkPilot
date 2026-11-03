package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

internal fun SQLiteDatabase.claimStudioDraft(
    draftId: String, expectedBody: String, editedBody: String, subscriptionId: Int,
): SendClaim? {
    require(editedBody.isNotBlank() && editedBody.length <= 800 && subscriptionId >= 0) { "Invalid send proposal" }
    return transaction {
        rawQuery("SELECT event_id, body, source_revision, state, thread_id, contact_id, recipient FROM held_drafts WHERE draft_id = ?", arrayOf(draftId)).use {
            if (!it.moveToFirst() || it.getString(3) != "HELD" || it.getString(1) != expectedBody) return@transaction null
            val source = studioSource(it.getString(0)) ?: return@transaction null
            if (source.staffPaused || source.revision != it.getLong(2) || source.threadId != it.getString(4) ||
                source.contactId != it.getString(5) || source.recipient != it.getString(6)
            ) return@transaction null
        }
        insertOrThrow("send_claims", null, ContentValues().apply {
            put("draft_id", draftId); put("body", editedBody); put("subscription_id", subscriptionId)
            put("claimed_at", System.currentTimeMillis())
        })
        update("held_drafts", ContentValues().apply { put("state", "SENDING") }, "draft_id = ?", arrayOf(draftId))
        checkNotNull(studioSendClaim(draftId))
    }
}

internal fun SQLiteDatabase.studioSendClaim(draftId: String): SendClaim? = rawQuery(
    """
        SELECT d.draft_id, d.event_id, d.thread_id, d.contact_id, d.recipient, s.body, s.subscription_id,
               d.source_revision, s.claimed_at, s.provider_id, d.state, s.error
        FROM send_claims s JOIN held_drafts d ON d.draft_id = s.draft_id WHERE s.draft_id = ?
    """.trimIndent(), arrayOf(draftId),
).use {
    if (!it.moveToFirst()) null else SendClaim(
        it.getString(0), it.getString(1), it.getString(2).toLong(), it.getString(3), it.getString(4),
        it.getString(5), it.getInt(6), it.getLong(7), it.getLong(8), if (it.isNull(9)) null else it.getLong(9),
        SendStatus.valueOf(it.getString(10)), if (it.isNull(11)) null else it.getString(11),
    )
}

internal fun SQLiteDatabase.attachStudioProviderId(draftId: String, providerId: Long): Boolean {
    require(providerId > 0)
    return transaction {
        val claim = studioSendClaim(draftId) ?: return@transaction false
        if (claim.status != SendStatus.SENDING || (claim.providerId != null && claim.providerId != providerId)) return@transaction false
        update("send_claims", ContentValues().apply { put("provider_id", providerId) }, "draft_id = ?", arrayOf(draftId)) == 1
    }
}

internal fun SQLiteDatabase.finishStudioSend(draftId: String, status: SendStatus, providerId: Long?, error: String?): Boolean {
    require(status != SendStatus.SENDING && (providerId == null || providerId > 0))
    require(error == null || error.length <= 256)
    return transaction {
        val claim = studioSendClaim(draftId) ?: return@transaction false
        if (providerId != null && claim.providerId != null && claim.providerId != providerId) return@transaction false
        if (claim.status != SendStatus.SENDING) return@transaction claim.status == status
        val values = ContentValues().apply {
            if (providerId != null) put("provider_id", providerId)
            if (error != null) put("error", error)
        }
        if (values.size() != 0) update("send_claims", values, "draft_id = ?", arrayOf(draftId))
        update("held_drafts", ContentValues().apply { put("state", status.name) }, "draft_id = ? AND state = 'SENDING'", arrayOf(draftId)) == 1
    }
}
