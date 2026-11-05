package org.fossify.messages.inkpilot.phone

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.InCallService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only qualification checks: never requests a role or places any call. */
@RunWith(AndroidJUnit4::class)
class PhoneRoleQualificationTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun qualifiesForDialAndInCallBinding() {
        val pm = context.packageManager
        listOf(Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_DIAL, Uri.parse("tel:5550100"))).forEach {
            assertNotNull(pm.resolveActivity(it.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY))
        }
        val info = pm.getServiceInfo(ComponentName(context, StudioInCallService::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_INCALL_SERVICE", info.permission)
        assertTrue(info.metaData.getBoolean("android.telecom.IN_CALL_SERVICE_UI"))
        val services = pm.queryIntentServices(Intent(InCallService.SERVICE_INTERFACE).setPackage(context.packageName), 0)
        assertTrue(services.any { it.serviceInfo.name == StudioInCallService::class.java.name })
    }

    @Test fun lockedMockNeverExposesCallerIncludingEmergencyMock() {
        listOf("5550100", "911", "112", null).forEach { mock ->
            assertEquals("Phone call · unlock for caller details", CallPresentation.identity(true, mock))
        }
        assertEquals("5550100", CallPresentation.identity(false, "5550100"))
        assertEquals("Private caller", CallPresentation.identity(false, null))
    }
}
