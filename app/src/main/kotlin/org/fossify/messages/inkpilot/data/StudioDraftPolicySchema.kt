package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** An additive marker: all pre-v10 held text is history, never newly qualified. */
internal object StudioDraftPolicySchema {
    const val COLUMN = "renderer_policy TEXT NOT NULL DEFAULT 'LEGACY_RAW' " +
        "CHECK(renderer_policy IN ('LEGACY_RAW','BOUNDED_INTENT_V1','STAFF_PROPOSAL_V1'))"
    // The additive column gives staff text a separate durable identity without modifying v10's checked renderer column.
    const val STAFF_COLUMN = "staff_question_policy TEXT " +
        "CHECK(staff_question_policy IS NULL OR staff_question_policy='STAFF_QUESTION_V1')"
    const val EFFECTIVE_POLICY = "(CASE WHEN d.kind='STAFF_QUESTION' AND d.renderer_policy='LEGACY_RAW' " +
        "AND d.staff_question_policy='STAFF_QUESTION_V1' THEN 'STAFF_QUESTION_V1' ELSE d.renderer_policy END)"

    // Shared by review and the transactional send claim, so a legacy row cannot look actionable.
    const val REVIEWABLE = "((d.kind='CUSTOMER_SERVICE_REPLY' AND d.renderer_policy='BOUNDED_INTENT_V1') " +
        "OR (d.kind='PROPOSAL_REPLY' AND d.renderer_policy='STAFF_PROPOSAL_V1') " +
        "OR (d.kind='STAFF_QUESTION' AND d.renderer_policy='LEGACY_RAW' " +
        "AND d.staff_question_policy='STAFF_QUESTION_V1'))"

    fun upgrade(db: SQLiteDatabase) {
        val present = db.rawQuery("PRAGMA table_info(held_drafts)", null).use { columns ->
            var found = false
            while (columns.moveToNext()) if (columns.getString(1) == "renderer_policy") found = true
            found
        }
        if (!present) db.execSQL("ALTER TABLE held_drafts ADD COLUMN $COLUMN")
    }

    /** Sparse historical tables and their send receipts are left intact. All old rows default NULL. */
    fun allowStaffQuestions(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE held_drafts ADD COLUMN $STAFF_COLUMN")
    }
}
