package org.fossify.messages.inkpilot.smoke

import android.content.ContentUris
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.data.SendStatus
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.runtime.AgentRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises Android telephony only on an emulator, never a physical SIM or live customer. */
@RunWith(AndroidJUnit4::class)
class NativeSendSmokeTest {
    @Test
    fun selectedEmulatorSimUsesProviderAndReplayGuard() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "This send test is emulator-only." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "device-test-evidence")
        val prior = JSONObject(File(directory, "local-loop.json").readText())
        val draftId = prior.getString("draftId")
        val body = prior.getString("body")
        require(prior.getString("recipient") == "+12025550123")
        val proof = JSONObject().put("carrierDeliveryTested", false)
        StudioRepository(context).use { repository ->
            assertThrows(IllegalArgumentException::class.java) {
                AgentRuntime.approveAndSend(context, draftId, body, Int.MAX_VALUE)
            }
            assertNull("An invalid SIM must not consume the draft", repository.getSendClaim(draftId))
            assertEquals(1, repository.heldDrafts().count { it.draftId == draftId })
            proof.put("invalidSimRejected", true)
            val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            proof.put("activeEmulatorSubscriptions", subscriptions.size)
            if (subscriptions.isEmpty()) {
                proof.put("nativeSendAttempted", false)
                proof.put("limitation", "Emulator has no active subscription; physical SIM test is still required.")
            } else {
                val chosen = subscriptions.first().subscriptionId
                proof.put("nativeSendAttempted", true)
                proof.put("selectedSubscriptionId", chosen)
                proof.put("result", AgentRuntime.approveAndSend(context, draftId, body, chosen))
                val claim = requireNotNull(repository.getSendClaim(draftId))
                val providerId = requireNotNull(claim.providerId)
                assertTrue(providerId > 0)
                assertEquals(chosen, claim.subscriptionId)
                assertEquals(body, claim.body)
                context.contentResolver.query(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId),
                    arrayOf("address", "body", "thread_id", "sub_id"), null, null, null,
                ).use { cursor ->
                    requireNotNull(cursor)
                    assertTrue("Approved reply must appear in the native provider", cursor.moveToFirst())
                    assertEquals("+12025550123", cursor.getString(0))
                    assertEquals(body, cursor.getString(1))
                    assertEquals(claim.threadId, cursor.getLong(2))
                    assertEquals(chosen, cursor.getInt(3))
                }
                assertThrows(IllegalStateException::class.java) {
                    AgentRuntime.approveAndSend(context, draftId, body, chosen)
                }
                assertEquals(providerId, repository.getSendClaim(draftId)?.providerId)
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (repository.getSendClaim(draftId)?.status == SendStatus.SENDING && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100)
                }
                proof.put("providerId", providerId)
                proof.put("replayRejected", true)
                proof.put("observedStatus", repository.getSendClaim(draftId)?.status?.name)
            }
        }
        File(directory, "native-send.json").writeText(proof.toString(2))
    }
}
