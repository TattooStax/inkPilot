package org.fossify.messages.inkpilot.smoke

import android.app.role.RoleManager
import android.provider.Telephony
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.DraftKey
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.staffAttention
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

/** Observes ordinary native inbox intake while unqualified; never invokes the draft runtime. */
@RunWith(AndroidJUnit4::class)
class WorkerWakeupSmokeTest {
    @Test
    fun incomingSmsStaysWithStaffWhileModelIsUnqualified() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = requireNotNull(InstrumentationRegistry.getArguments().getString("workerMessage"))
        val evidence = File(context.filesDir, "device-test-evidence").apply { mkdirs() }
        assertFalse("The qualification policy must survive app restarts", AgentSettings(context).enabled)
        val roles = context.getSystemService(RoleManager::class.java)
        assertTrue(roles.isRoleAvailable(RoleManager.ROLE_SMS))
        assertTrue("inkPilot must hold the real SMS role", roles.isRoleHeld(RoleManager.ROLE_SMS))
        StudioRepository(context).use { repository ->
            assertTrue(repository.contacts().none { it.number == "+12025550124" })
            ActivityScenario.launch(StudioHomeActivity::class.java).use {
                SmokeViews.assertText("New message")
                File(evidence, "worker-ready").writeText("waiting for synthetic SMS")
                val started = System.currentTimeMillis()
                val source = SmokeViews.await("second native SMS intake", 60_000) {
                    repository.contacts().mapNotNull { contact -> contact.latestEventId?.let(repository::readSource) }
                        .firstOrNull { arrival -> arrival.inboundText == message }
                }
                assertEquals("+12025550124", source.recipient)
                assertFalse(repository.isDraftingEnabled())
                assertNull(repository.findHeldDraft(DraftKey(source.eventId)))
                assertTrue("New SMS after the old ON bit was cleared stays in the ordinary inbox",
                    repository.staffAttention().none { it.eventId == source.eventId })
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI, arrayOf("thread_id"), "body = ?", arrayOf(message), null,
                ).use { cursor ->
                    requireNotNull(cursor)
                    assertEquals(1, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(source.threadId.toLong(), cursor.getLong(0))
                }
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, arrayOf("_id"), "type IN (2, 4, 5, 6)", null, null,
                ).use { cursor ->
                    assertEquals("Unqualified intake must not send", 0, requireNotNull(cursor).count)
                }
                File(evidence, "worker-wakeup.json").writeText(JSONObject().apply {
                    put("eventId", source.eventId)
                    put("recipient", source.recipient)
                    put("outcome", "BLOCKED_UNQUALIFIED")
                    put("sourceRevision", source.revision)
                    put("elapsedMillis", System.currentTimeMillis() - started)
                    put("trigger", "Native SMS receiver to ordinary inbox")
                    put("directRuntimeCall", false)
                    put("modelInvoked", false)
                    put("automaticSend", false)
                    put("carrierDeliveryTested", false)
                }.toString(2))
            }
        }
    }
}
