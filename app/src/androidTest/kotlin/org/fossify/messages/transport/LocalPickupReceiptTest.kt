package org.fossify.messages.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import inkstax.sms.transport.DeviceCredential
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LocalPickupReceiptTest {
    @Test
    fun repeatedPickupNeverResubmitsAnUnconfirmedSourceAfterCallbackCleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credential = DeviceCredential("http://127.0.0.1:13133", "fixture", "fixture-key")
        val id = UUID.randomUUID().toString()
        val marker = progressKeyStem(credential.endpointBase, credential.deviceId, id) + "_dispatched"
        try {
            assertFalse(context.alreadyDispatched(credential, id))
            assertTrue(context.beginOutboundSendProgress(credential.endpointBase, credential.deviceId, id, 1))
            assertTrue(context.alreadyDispatched(credential, id))
            context.clearOutboundSendProgress(credential.endpointBase, credential.deviceId, id)
            assertTrue(context.alreadyDispatched(credential, id))
            assertFalse(context.alreadyDispatched(credential.copy(deviceId = "other"), id))
        } finally {
            context.clearOutboundSendProgress(credential.endpointBase, credential.deviceId, id)
            context.progressPrefs().edit().remove(marker).commit()
        }
    }
}
