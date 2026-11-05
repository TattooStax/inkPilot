package org.fossify.messages.inkpilot.smoke

import android.app.role.RoleManager
import android.os.Build
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.DraftKey
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.staffAttention
import org.fossify.messages.inkpilot.runtime.AgentRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native token-budget failure must not starve the next contact in the same batch. */
@RunWith(AndroidJUnit4::class)
class BatchFailureSmokeTest {
    @Test
    fun oversizedNativeContextDoesNotBlockFollowingLocalReply() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Batch fixtures run only on an emulator." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(context.filesDir, "device-test-evidence")
        check(File(evidence, "native-send.json").isFile) { "Run only after the full synthetic SMS proof." }
        check(File(context.filesDir, "models/active.gguf").isFile)
        val roles = context.getSystemService(RoleManager::class.java)
        assertTrue(roles.isRoleAvailable(RoleManager.ROLE_SMS) && roles.isRoleHeld(RoleManager.ROLE_SMS))
        val failedEvent = "smoke:batch-overflow"
        val nextEvent = "smoke:batch-following"
        val memory = buildString { repeat(8000) { append((0x4E00 + it).toChar()) } }
        assertEquals(8000, memory.length)
        val outgoingBefore = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id"), "type IN (2, 4, 5, 6)", null, null,
        ).use { requireNotNull(it).count }

        // This is the production worker lock. Reentrant processPending runs exactly once;
        // a queued worker cannot hide an early-return bug by processing the second contact.
        synchronized(AgentRuntime.inferenceLock) {
            StudioRepository(context).use { repository ->
                assertTrue(repository.pendingRequests().isEmpty())
                assertTrue(repository.contacts().none { it.number in setOf("+12025550125", "+12025550126") })
                repository.setDraftingEnabled(false)
                val arrivedAt = System.currentTimeMillis()
                val failed = repository.recordInbound(
                    failedEvent, 900_125, "+12025550125", "Can I book a consultation?",
                    "Synthetic context limit", arrivedAt, -1,
                )
                repository.saveMemory(failed.contactId, memory)
                repository.recordInbound(
                    nextEvent, 900_126, "+12025550126", "Can I book a rose tattoo consultation?",
                    "Synthetic following contact", arrivedAt + 1, -1,
                )
                repository.setDraftingEnabled(true)
                assertEquals(listOf(failedEvent, nextEvent), repository.pendingRequests().map { it.eventId })
                val failedSource = requireNotNull(repository.readSource(failedEvent))
                assertFalse(failedSource.staffPaused)
                val started = System.currentTimeMillis()
                val created = AgentRuntime.processPending(context)
                assertNull(repository.findHeldDraft(DraftKey(failedEvent)))
                val receipt = repository.findHeldDraft(DraftKey(nextEvent))
                if (receipt == null) {
                    assertEquals("Only a policy block can stop the second model reply", 0, created)
                    assertEquals("UNSAFE_CONTENT", repository.staffAttention().single { it.eventId == nextEvent }.reason)
                } else {
                    assertEquals(1, created)
                    assertEquals("+12025550126", receipt.recipient)
                    assertTrue(receipt.body.isNotBlank() && receipt.body.length <= 800)
                    assertFalse(receipt.body.contains("<think>") || receipt.body.contains("<tool_call>"))
                    assertTrue(repository.heldDrafts().any { it.draftId == receipt.draftId && it.canReview })
                }
                repository.database.rawQuery(
                    "SELECT reason FROM draft_attempts WHERE event_id = ? AND source_revision = ?",
                    arrayOf(failedEvent, failedSource.revision.toString()),
                ).use { cursor ->
                    assertEquals(1, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Model or source read failed", cursor.getString(0))
                }
                StudioRepository(context).use { reopened ->
                    assertTrue("Failure attempt and second receipt must survive reopening", reopened.pendingRequests().isEmpty())
                    assertNull(reopened.findHeldDraft(DraftKey(failedEvent)))
                    assertEquals(receipt, reopened.findHeldDraft(DraftKey(nextEvent)))
                    if (receipt == null) assertEquals("UNSAFE_CONTENT",
                        reopened.staffAttention().single { it.eventId == nextEvent }.reason)
                }
                File(evidence, "batch-failure.json").writeText(JSONObject().apply {
                    put("singleProductionBatchCalls", 1)
                    put("nextOutcome", if (receipt == null) "BLOCKED_UNSAFE_CONTENT" else "HELD")
                    put("failedEventId", failedEvent)
                    put("failedMemoryCharacters", memory.length)
                    put("failedSourceRevision", failedSource.revision)
                    put("failureAttemptDurable", true)
                    put("nextEventId", nextEvent)
                    if (receipt != null) { put("draftId", receipt.draftId); put("body", receipt.body) }
                    put("elapsedMillis", System.currentTimeMillis() - started)
                    put("carrierDeliveryTested", false)
                }.toString(2))
            }
        }
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id"), "type IN (2, 4, 5, 6)", null, null,
        ).use { assertEquals("Batch generation must not send", outgoingBefore, requireNotNull(it).count) }
    }
}
