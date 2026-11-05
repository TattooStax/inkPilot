package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** Additive v5→v6 ledger. A unique source/payload receipt prevents replay after restart. */
internal object StudioProposalSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE action_proposals (
            id TEXT PRIMARY KEY NOT NULL,
            event_id TEXT NOT NULL REFERENCES inbound_events(event_id),
            kind TEXT NOT NULL CHECK(kind IN ('APPOINTMENT','MEMORY_CORRECTION','DRAFT_REPLY')),
            contact_id TEXT NOT NULL REFERENCES studio_contacts(id),
            thread_id TEXT NOT NULL,
            source_revision INTEGER NOT NULL CHECK(source_revision >= 0),
            payload TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'HELD' CHECK(state IN ('HELD','APPROVED','DISMISSED','STALE')),
            receipt TEXT,
            staff_id INTEGER REFERENCES studio_staff(id),
            created_at INTEGER NOT NULL,
            confirmed_at INTEGER,
            UNIQUE(event_id,kind,source_revision,payload)
        )""")
        db.execSQL("CREATE INDEX action_proposals_state_created ON action_proposals(state,created_at)")
        db.execSQL("CREATE INDEX action_proposals_contact ON action_proposals(contact_id,created_at)")
    }
}
