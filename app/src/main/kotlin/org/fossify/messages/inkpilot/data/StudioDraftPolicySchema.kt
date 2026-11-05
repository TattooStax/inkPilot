package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** An additive marker: all pre-v10 held text is history, never newly qualified. */
internal object StudioDraftPolicySchema {
    const val COLUMN = "renderer_policy TEXT NOT NULL DEFAULT 'LEGACY_RAW' " +
        "CHECK(renderer_policy IN ('LEGACY_RAW','BOUNDED_INTENT_V1','STAFF_PROPOSAL_V1'))"

    // Shared by review and the transactional send claim, so a legacy row cannot look actionable.
    const val REVIEWABLE = "((d.kind='CUSTOMER_SERVICE_REPLY' AND d.renderer_policy='BOUNDED_INTENT_V1') " +
        "OR (d.kind='PROPOSAL_REPLY' AND d.renderer_policy='STAFF_PROPOSAL_V1'))"

    fun upgrade(db: SQLiteDatabase) {
        val present = db.rawQuery("PRAGMA table_info(held_drafts)", null).use { columns ->
            var found = false
            while (columns.moveToNext()) if (columns.getString(1) == "renderer_policy") found = true
            found
        }
        if (!present) db.execSQL("ALTER TABLE held_drafts ADD COLUMN $COLUMN")
    }
}
