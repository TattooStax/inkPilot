package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Reconcile Android history independently of sending; this queue never dispatches SMS. */
internal fun SQLiteDatabase.pendingStudioProviderUpdates(limit: Int): List<SendClaim> {
    require(limit in 1..500)
    val ids = rawQuery(
        """
            SELECT s.draft_id FROM send_claims s JOIN held_drafts d ON d.draft_id = s.draft_id
            WHERE s.provider_synced = 0 AND s.provider_id IS NOT NULL AND d.state IN ('SENT', 'FAILED')
            ORDER BY s.claimed_at, s.draft_id LIMIT ?
        """.trimIndent(), arrayOf(limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    return ids.mapNotNull { studioSendClaim(it) }
}

internal fun SQLiteDatabase.markStudioProviderSynced(draftId: String, providerId: Long): Boolean {
    require(providerId > 0)
    return transaction {
        val claim = studioSendClaim(draftId) ?: return@transaction false
        if (claim.providerId != providerId || claim.status !in setOf(SendStatus.SENT, SendStatus.FAILED)) return@transaction false
        update("send_claims", ContentValues().apply { put("provider_synced", 1) }, "draft_id = ? AND provider_id = ?", arrayOf(draftId, providerId.toString())) == 1
    }
}
