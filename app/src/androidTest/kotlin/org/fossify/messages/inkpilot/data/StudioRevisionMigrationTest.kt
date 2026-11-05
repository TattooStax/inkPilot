package org.fossify.messages.inkpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.HeldDraftWrite
import inkpilot.agent.DraftKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioRevisionMigrationTest {
    @Test
    fun actualV1UpgradeRetainsReceiptsClaimsPartsSettingsAndAllowsNewRevision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        try {
            StudioV1Fixture(context, name).use { helper ->
                val db = helper.writableDatabase
                db.execSQL("UPDATE agent_settings SET drafting_enabled = 1, soul = 'Original guide'")
                db.execSQL("INSERT INTO studio_contacts(id,number,display_name,revision,latest_event_id) VALUES('c','+15551234567','Synthetic',1,'e')")
                db.execSQL("INSERT INTO inbound_events VALUES('e','42','c','+15551234567','Synthetic request',1000,3,1)")
                db.execSQL("INSERT INTO held_drafts VALUES('held','e','CUSTOMER_SERVICE_REPLY','42','c','+15551234567',1,'Original draft','HELD',1000)")
                db.execSQL("INSERT INTO studio_contacts(id,number,display_name,revision,latest_event_id) VALUES('s','+15557654321','Claimed',1,'sent')")
                db.execSQL("INSERT INTO inbound_events VALUES('sent','43','s','+15557654321','Synthetic sent request',1001,3,1)")
                db.execSQL("INSERT INTO held_drafts VALUES('claimed','sent','CUSTOMER_SERVICE_REPLY','43','s','+15557654321',1,'Approved text','UNKNOWN',1001)")
                db.execSQL("INSERT INTO send_claims VALUES('claimed','Edited approved text',3,1002,123,0,'Ambiguous')")
                db.execSQL("INSERT INTO send_parts VALUES('claimed',0,1,NULL)")
                assertEquals(1, db.version)
            }
            StudioRepository(context, name).use { repository ->
                assertEquals(9, repository.database.version)
                assertEquals("Original guide", repository.soul())
                assertEquals("Edited approved text", repository.getSendClaim("claimed")?.body)
                assertEquals(SendStatus.UNKNOWN, repository.getSendClaim("claimed")?.status)
                repository.database.rawQuery("SELECT outcome FROM send_parts WHERE draft_id = 'claimed'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                repository.saveMemory("c", "Revised memory")
                val source = checkNotNull(repository.readSource("e"))
                assertTrue(repository.regenerate("e", source.revision) is DraftRegenerationOutcome.Held)
                assertTrue(repository.holdIfCurrent(HeldDraftWrite(DraftKey("e"), "42", "c", source.recipient, source.revision, "Revised answer")) is DraftStoreResult.Held)
                assertEquals(3, repository.drafts().size)
                assertEquals("Original draft", repository.drafts().single { it.draftId == "held" }.body)
                repository.database.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                repository.advanceIntakeCheckpoint(201)
            }
            StudioRepository(context, name).use {
                assertEquals(201L, it.intakeCheckpoint())
                assertEquals(3, it.drafts().size)
                assertEquals(DraftRegenerationOutcome.AlreadyClaimed, it.regenerate("sent", 1))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
