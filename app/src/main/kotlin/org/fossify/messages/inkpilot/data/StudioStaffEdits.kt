package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

private const val MAX_STAFF_REPLY_LENGTH = 800

fun StudioRepository.saveStaffEdit(draftId: String, body: String): Boolean =
    database.saveStudioStaffEdit(draftId, body)

internal fun SQLiteDatabase.saveStudioStaffEdit(draftId: String, body: String): Boolean {
    require(body.length <= MAX_STAFF_REPLY_LENGTH) { "Replies must be at most 800 characters." }
    return update("held_drafts", ContentValues().apply { put("staff_edit_body", body) },
        "draft_id = ? AND state = 'HELD'", arrayOf(draftId)) == 1
}
