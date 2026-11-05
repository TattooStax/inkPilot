package org.fossify.messages.messaging

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkstax.sms.transport.OutboundSms
import inkstax.sms.transport.TransportOutcome
import org.fossify.messages.transport.LocalMessageTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** A context that cannot reach a provider or radio makes early refusal executable. */
@RunWith(AndroidJUnit4::class)
class GroupSmsRefusalTest {
    private val noIo = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getContentResolver(): ContentResolver = error("Provider touched")
        override fun getSystemService(name: String): Any? = error("Radio touched: $name")
        override fun getPackageManager(): PackageManager = error("Role lookup touched")
    }

    @Test fun commonTransportRefusesMultipleRecipientsBeforeAnyDeviceIo() {
        val result = LocalMessageTransport(noIo).sendSms(OutboundSms(
            "Tattoo appointment", listOf("+12025550111", "+12025550112"), 1, false))
        assertEquals(TransportOutcome.Failed(TransportOutcome.UNKNOWN_ERROR, SMS_GROUP_REFUSAL), result)
    }

    @Test fun nativeSenderCannotInsertFabricatedGroupSentRow() {
        try {
            MessagingUtils(noIo).sendSmsMessage("Tattoo appointment",
                setOf("+12025550111", "+12025550112"), 1, false)
            fail("Group SMS must be refused")
        } catch (e: SmsException) {
            assertEquals(SmsException.ERROR_SENDING_MESSAGE, e.errorCode)
        }
    }
}
