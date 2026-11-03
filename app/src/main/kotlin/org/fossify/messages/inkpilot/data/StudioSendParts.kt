package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

internal fun SQLiteDatabase.prepareStudioSendParts(draftId: String, count: Int): Boolean {
    require(count in 1..32)
    return transaction {
        if (studioSendClaim(draftId)?.status != SendStatus.SENDING) return@transaction false
        rawQuery("SELECT 1 FROM send_parts WHERE draft_id = ? LIMIT 1", arrayOf(draftId)).use {
            if (it.moveToFirst()) return@transaction false
        }
        repeat(count) { index ->
            insertOrThrow("send_parts", null, ContentValues().apply { put("draft_id", draftId); put("part_index", index) })
        }
        true
    }
}

internal fun SQLiteDatabase.recordStudioSendPart(draftId: String, partIndex: Int, success: Boolean, error: String?): SendStatus? {
    require(partIndex in 0..31 && (error == null || error.length <= 256))
    return transaction {
        val claim = studioSendClaim(draftId) ?: return@transaction null
        val previous = rawQuery("SELECT outcome FROM send_parts WHERE draft_id = ? AND part_index = ?", arrayOf(draftId, partIndex.toString())).use {
            if (!it.moveToFirst()) return@transaction null
            it.getInt(0)
        }
        if (claim.status != SendStatus.SENDING) return@transaction claim.status
        if (previous == 0) {
            update("send_parts", ContentValues().apply {
                put("outcome", if (success) 1 else 2)
                if (error != null) put("error", error)
            }, "draft_id = ? AND part_index = ? AND outcome = 0", arrayOf(draftId, partIndex.toString()))
        }
        var waiting = false
        var failure: String? = null
        rawQuery("SELECT outcome, error FROM send_parts WHERE draft_id = ? ORDER BY part_index", arrayOf(draftId)).use {
            while (it.moveToNext()) {
                if (it.getInt(0) == 0) waiting = true
                if (it.getInt(0) == 2) failure = if (it.isNull(1)) "Carrier rejected a message part" else it.getString(1)
            }
        }
        val status = when {
            failure != null -> SendStatus.FAILED
            waiting -> SendStatus.SENDING
            else -> SendStatus.SENT
        }
        if (status != SendStatus.SENDING) {
            update("held_drafts", ContentValues().apply { put("state", status.name) }, "draft_id = ? AND state = 'SENDING'", arrayOf(draftId))
            if (failure != null) update("send_claims", ContentValues().apply { put("error", failure) }, "draft_id = ?", arrayOf(draftId))
        }
        status
    }
}
