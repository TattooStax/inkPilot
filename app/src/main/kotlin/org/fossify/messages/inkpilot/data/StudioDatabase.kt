package org.fossify.messages.inkpilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import org.fossify.messages.inkpilot.studio.StudioFeatureSchema

/** Business records are never placed in the inherited, destructively rebuildable SMS cache. */
internal class StudioDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 6) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    // Separate short-lived repositories can open the same new database concurrently.
    override fun getWritableDatabase(): SQLiteDatabase = synchronized(openLock) { super.getWritableDatabase() }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS held_thread_state_created ON held_drafts(thread_id, state, created_at)")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE agent_settings (id INTEGER PRIMARY KEY CHECK(id = 1), drafting_enabled INTEGER NOT NULL DEFAULT 0, soul TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO agent_settings(id) VALUES(1)")
        db.execSQL("""
            CREATE TABLE studio_contacts (
                id TEXT PRIMARY KEY NOT NULL,
                number TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                memory TEXT NOT NULL DEFAULT '',
                paused INTEGER NOT NULL DEFAULT 0 CHECK(paused IN (0, 1)),
                revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                latest_event_id TEXT,
                last_message_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE inbound_events (
                event_id TEXT PRIMARY KEY NOT NULL,
                thread_id TEXT NOT NULL,
                contact_id TEXT NOT NULL REFERENCES studio_contacts(id),
                recipient TEXT NOT NULL,
                body TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                subscription_id INTEGER NOT NULL,
                created_revision INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX inbound_contact ON inbound_events(contact_id)")
        db.execSQL("""
            CREATE TABLE draft_attempts (
                event_id TEXT NOT NULL REFERENCES inbound_events(event_id),
                source_revision INTEGER NOT NULL,
                reason TEXT NOT NULL,
                PRIMARY KEY(event_id, source_revision)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE held_drafts (
                draft_id TEXT PRIMARY KEY NOT NULL,
                event_id TEXT NOT NULL REFERENCES inbound_events(event_id),
                kind TEXT NOT NULL,
                thread_id TEXT NOT NULL,
                contact_id TEXT NOT NULL REFERENCES studio_contacts(id),
                recipient TEXT NOT NULL,
                source_revision INTEGER NOT NULL,
                body TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'HELD',
                created_at INTEGER NOT NULL,
                UNIQUE(event_id, kind, source_revision)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX held_state_created ON held_drafts(state, created_at)")
        db.execSQL("""
            CREATE TABLE send_claims (
                draft_id TEXT PRIMARY KEY NOT NULL REFERENCES held_drafts(draft_id),
                body TEXT NOT NULL,
                subscription_id INTEGER NOT NULL,
                claimed_at INTEGER NOT NULL,
                provider_id INTEGER,
                provider_synced INTEGER NOT NULL DEFAULT 0 CHECK(provider_synced IN (0, 1)),
                error TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE send_parts (
                draft_id TEXT NOT NULL REFERENCES send_claims(draft_id),
                part_index INTEGER NOT NULL,
                outcome INTEGER NOT NULL DEFAULT 0 CHECK(outcome IN (0, 1, 2)),
                error TEXT,
                PRIMARY KEY(draft_id, part_index)
            )
        """.trimIndent())
        StudioFeatureSchema.create(db)
        StudioRevisionSchema.create(db)
        StudioIdentitySchema.create(db)
        org.fossify.messages.inkpilot.studio.StudioImageLinkSchema.create(db)
        org.fossify.messages.inkpilot.studio.StudioCalendarSchema.create(db)
        StudioProposalSchema.create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 1..5 || newVersion != 6) throw SQLiteException("No studio migration from $oldVersion to $newVersion")
        if (oldVersion == 1) StudioRevisionSchema.upgrade(db)
        if (oldVersion <= 2) StudioIdentitySchema.create(db)
        if (oldVersion <= 3) org.fossify.messages.inkpilot.studio.StudioImageLinkSchema.create(db)
        if (oldVersion <= 4) org.fossify.messages.inkpilot.studio.StudioCalendarSchema.upgrade(db)
        StudioProposalSchema.create(db)
    }

    companion object { private val openLock = Any() }
}

internal inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
    beginTransaction()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
