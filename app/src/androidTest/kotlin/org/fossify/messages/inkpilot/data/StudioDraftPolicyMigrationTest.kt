package org.fossify.messages.inkpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftRendererPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** A frozen v9 row with its ON bit must never become a v10 sendable bounded reply. */
@RunWith(AndroidJUnit4::class)
class StudioDraftPolicyMigrationTest {
    @Test fun v9HeldRawRowRemainsHistoryAndCannotClaimAfterUpgradeOrReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "draft-policy-v9-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE agent_settings(id INTEGER PRIMARY KEY, drafting_enabled INTEGER NOT NULL, soul TEXT NOT NULL)")
                db.execSQL("INSERT INTO agent_settings VALUES(1,1,'Older guide')")
                db.execSQL("""CREATE TABLE studio_contacts(id TEXT PRIMARY KEY, number TEXT, display_name TEXT,
                    memory TEXT, paused INTEGER, revision INTEGER, latest_event_id TEXT, last_message_at INTEGER)""")
                db.execSQL("INSERT INTO studio_contacts VALUES('c','+15551234567','Client','',0,7,'e',1000)")
                db.execSQL("""CREATE TABLE inbound_events(event_id TEXT PRIMARY KEY, thread_id TEXT,
                    contact_id TEXT, recipient TEXT, body TEXT, received_at INTEGER,
                    subscription_id INTEGER, created_revision INTEGER)""")
                db.execSQL("INSERT INTO inbound_events VALUES('e','42','c','+15551234567','Old question',1000,3,7)")
                db.execSQL("""CREATE TABLE held_drafts(draft_id TEXT PRIMARY KEY, event_id TEXT, kind TEXT,
                    thread_id TEXT, contact_id TEXT, recipient TEXT, source_revision INTEGER,
                    body TEXT, staff_edit_body TEXT, state TEXT, created_at INTEGER)""")
                db.execSQL("""INSERT INTO held_drafts VALUES('old','e','CUSTOMER_SERVICE_REPLY','42',
                    'c','+15551234567',7,'Your appointment is confirmed.',NULL,'HELD',1000)""")
                db.execSQL("""CREATE TABLE send_claims(draft_id TEXT PRIMARY KEY, body TEXT,
                    subscription_id INTEGER, claimed_at INTEGER, provider_id INTEGER,
                    provider_synced INTEGER, error TEXT)""")
                db.version = 9
            }
            repeat(2) {
                StudioRepository(context, name).use { repository ->
                    assertEquals(11, repository.database.version)
                    val receipt = requireNotNull(repository.findHeldDraft(DraftKey("e")))
                    assertEquals(DraftRendererPolicy.LEGACY_RAW, receipt.rendererPolicy)
                    val old = requireNotNull(repository.draft("old"))
                    assertFalse(old.canReview)
                    assertEquals(DraftRendererPolicy.LEGACY_RAW, old.rendererPolicy)
                    assertNull(repository.claimDraftForSend("old", old.body, old.body, 3))
                    assertNull(repository.getSendClaim("old"))
                    repository.database.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                    assertTrue(repository.isDraftingEnabled()) // Even a saved ON bit cannot requalify old text.
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
