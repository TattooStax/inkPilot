package org.fossify.messages.inkpilot.runtime

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.extensions.config
import org.fossify.messages.messaging.DispatchOutcome
import org.fossify.messages.messaging.isLongMmsMessage
import org.fossify.messages.messaging.sendMessageCompat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A headless human reply must not hand off a text-only MMS without a callback claim. */
@RunWith(AndroidJUnit4::class)
class HumanReplySmsOnlyTest {
    @Test fun longReplyConfiguredAsMmsIsHeldBeforeAnyCarrierHandoff() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val previous = context.config.sendLongMessageMMS
        try {
            context.config.sendLongMessageMMS = true
            val body = "Reference details ".repeat(30)
            assertTrue(context.isLongMmsMessage(body))
            val result = context.sendMessageCompat(body, listOf("+12025550177"), -1,
                emptyList(), smsOnly = true)
            assertTrue(result is DispatchOutcome.Rejected && result.reason.contains("MMS"))
        } finally { context.config.sendLongMessageMMS = previous }
    }
}
