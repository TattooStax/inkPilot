package org.fossify.messages.inkpilot.smoke

import android.app.role.RoleManager
import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.DraftKey
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.staffAttention
import org.fossify.messages.inkpilot.runtime.AgentRuntime
import org.fossify.messages.inkpilot.runtime.AgentSettings
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Staged by inkpilot-device-run.sh on a fresh emulator, with real local model weights. */
@RunWith(AndroidJUnit4::class)
class LocalLoopSmokeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val message: String get() = requireNotNull(
        InstrumentationRegistry.getArguments().getString("fixtureMessage")
    ) { "Use scripts/inkpilot-device-run.sh to install the model and inject the synthetic SMS." }
    private val evidence: File get() = File(context.filesDir, "device-test-evidence").apply { mkdirs() }

    @Test
    fun prepareForSyntheticSms() {
        assertSmsRoleHeld()
        AgentSettings(context).apply {
            enabled = false
            soul = "You help tattoo studio staff. Keep replies brief. Never invent prices or appointments."
        }
        StudioRepository(context).use { repository ->
            assertTrue("Use a fresh emulator; this test never clears existing customer records", repository.contacts().isEmpty())
            assertTrue(repository.heldDrafts().isEmpty())
        }
        assertEquals(0, outgoingMessages())
    }

    @Test
    fun incomingSmsNeedsStaffWhileModelIsUnqualified() {
        assertSmsRoleHeld()
        assertTrue("The checksum-pinned GGUF must be installed", File(context.filesDir, "models/active.gguf").isFile)
        StudioRepository(context).use { repository ->
            File(evidence, "local-loop-ready").writeText("waiting for synthetic SMS")
            val source = try {
                SmokeViews.await("native SMS intake") {
                    repository.contacts().mapNotNull { it.latestEventId?.let(repository::readSource) }
                        .firstOrNull { it.inboundText == message }
                }
            } catch (failure: AssertionError) {
                val nativeRows = context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), "body = ?", arrayOf(message), null,
                ).use { requireNotNull(it).count }
                val diagnostic = JSONObject().put("matchingNativeInboxRows", nativeRows)
                    .put("businessContacts", repository.contacts().size).put("runtimeStatus", AgentSettings(context).status)
                File(evidence, "intake-failure.json").writeText(diagnostic.toString(2))
                throw AssertionError("Native SMS intake did not arrive: $diagnostic", failure)
            }
            assertEquals("+12025550123", source.recipient)
            assertProviderArrival(source.threadId.toLong())
            repository.saveMemory(source.contactId, "Staff-approved note: consultations last 30 minutes.")
            // An older ON bit must still be closed when the installed model has not qualified.
            repository.setDraftingEnabled(true)
            val current = requireNotNull(repository.readSource(source.eventId))
            assertTrue("Memory changes must invalidate older inference", current.revision > source.revision)

            val started = System.currentTimeMillis()
            val created = AgentRuntime.processPending(context)
            val elapsed = System.currentTimeMillis() - started
            assertEquals("An unqualified model must never run", 0, created)
            assertFalse(repository.isDraftingEnabled())
            assertNull(repository.findHeldDraft(DraftKey(current.eventId)))
            val attention = repository.staffAttention().single { it.eventId == current.eventId && it.current }
            assertEquals("MODEL_UNQUALIFIED", attention.reason)
            assertTrue(attention.sourceRevision > current.revision)
            assertEquals("A blocked model must not send SMS", 0, outgoingMessages())
            File(evidence, "local-loop.json").writeText(JSONObject().apply {
                put("outcome", "BLOCKED_UNQUALIFIED")
                put("eventId", current.eventId)
                put("sourceRevision", attention.sourceRevision)
                put("recipient", current.recipient)
                put("observedWaitMillis", elapsed)
                put("modelInstalled", "LiquidAI/LFM3-1.7B-QAD-Q4_0")
                put("modelInvoked", false)
                put("carrierDeliveryTested", false)
            }.toString(2))
            ActivityScenario.launch(StudioHomeActivity::class.java).use {
                SmokeViews.clickDescription("Open agent controls")
                SmokeViews.clickText("Drafts & staff replies")
                try {
                    SmokeViews.assertText("Staff reply needed")
                    SmokeViews.assertText("Open conversation")
                } catch (failure: AssertionError) {
                    SmokeViews.capture(File(evidence, "local-staff-attention.png"))
                    throw AssertionError("Unqualified model blocked, but staff attention was not visible", failure)
                }
                SmokeViews.capture(File(evidence, "local-staff-attention.png"))
            }
            assertEquals("Opening staff review must not send SMS", 0, outgoingMessages())
        }
    }

    @Test
    fun blockedUnqualifiedStateSurvivesProcessRestart() {
        val proof = JSONObject(File(evidence, "local-loop.json").readText())
        StudioRepository(context).use { repository ->
            val key = DraftKey(proof.getString("eventId"))
            assertEquals("BLOCKED_UNQUALIFIED", proof.getString("outcome"))
            assertFalse(repository.isDraftingEnabled())
            assertNull(repository.findHeldDraft(key))
            val attention = repository.staffAttention().single { it.eventId == key.eventId && it.current }
            assertEquals("MODEL_UNQUALIFIED", attention.reason)
            assertEquals(proof.getLong("sourceRevision"), attention.sourceRevision)
            assertEquals(0, AgentRuntime.processPending(context))
            assertNull(repository.findHeldDraft(key))
            assertEquals(attention, repository.staffAttention().single { it.eventId == key.eventId && it.current })
            assertEquals(0, outgoingMessages())
        }
    }

    private fun assertProviderArrival(threadId: Long) {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf("thread_id", "address"),
            "body = ?", arrayOf(message), null,
        ).use { cursor ->
            requireNotNull(cursor)
            assertEquals("SMS must exist in the native conversation provider", 1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(threadId, cursor.getLong(0))
            assertEquals("+12025550123", cursor.getString(1))
        }
    }

    private fun outgoingMessages(): Int = context.contentResolver.query(
        Telephony.Sms.CONTENT_URI, arrayOf("_id"), "type IN (2, 4, 5, 6)", null, null,
    ).use { requireNotNull(it).count }

    private fun assertSmsRoleHeld() {
        val roles = context.getSystemService(RoleManager::class.java)
        assertTrue("Android must offer the SMS role", roles.isRoleAvailable(RoleManager.ROLE_SMS))
        assertTrue("inkPilot must hold the real SMS role", roles.isRoleHeld(RoleManager.ROLE_SMS))
    }
}
