package org.fossify.messages.inkpilot.smoke

import android.provider.Telephony
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.DraftKey
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.runtime.AgentSettings
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Observes the receiver/WorkManager path; never schedules work or calls the draft runtime. */
@RunWith(AndroidJUnit4::class)
class WorkerWakeupSmokeTest {
    @Test
    fun incomingSmsAutomaticallyWakesLocalWorkerAndHoldsReply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = requireNotNull(InstrumentationRegistry.getArguments().getString("workerMessage"))
        val evidence = File(context.filesDir, "device-test-evidence").apply { mkdirs() }
        assertTrue("The real enabled setting must survive prior app restarts", AgentSettings(context).enabled)
        assertEquals(context.packageName, Telephony.Sms.getDefaultSmsPackage(context))
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
                val receipt = SmokeViews.await("automatic WorkManager local-model draft", 240_000) {
                    repository.findHeldDraft(DraftKey(source.eventId))
                }
                assertEquals(source.contactId, receipt.contactId)
                assertEquals(source.recipient, receipt.recipient)
                assertEquals(source.revision, receipt.sourceRevision)
                assertTrue(receipt.body.isNotBlank() && receipt.body.length <= 800)
                assertEquals(1, repository.heldDrafts().count { draft -> draft.draftId == receipt.draftId && draft.canReview })
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
                    assertEquals("Automatic wakeup must hold the reply without sending", 0, requireNotNull(cursor).count)
                }
                File(evidence, "worker-wakeup.json").writeText(JSONObject().apply {
                    put("eventId", source.eventId)
                    put("draftId", receipt.draftId)
                    put("recipient", receipt.recipient)
                    put("body", receipt.body)
                    put("elapsedMillis", System.currentTimeMillis() - started)
                    put("trigger", "SMS receiver to WorkManager")
                    put("directRuntimeCall", false)
                    put("automaticSend", false)
                    put("carrierDeliveryTested", false)
                }.toString(2))
            }
        }
    }
}
