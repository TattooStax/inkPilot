package org.fossify.messages.inkpilot.phone

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Preflight and recovery routing; no role chooser, notification setting, or carrier call is invoked. */
@RunWith(AndroidJUnit4::class)
class PhoneCallAlertsTest {
    @Test fun deniedPostOrDisabledAppBlocksRolePreflight() {
        assertEquals(PhoneCallAlerts.Block.POST, PhoneCallAlerts.evaluate(
            false, true, NotificationManager.IMPORTANCE_HIGH, true))
        assertEquals(PhoneCallAlerts.Block.APP, PhoneCallAlerts.evaluate(
            true, false, NotificationManager.IMPORTANCE_HIGH, true))
    }

    @Test fun quietChannelOrDeniedFullScreenBlocksRolePreflight() {
        assertEquals(PhoneCallAlerts.Block.CHANNEL,
            PhoneCallAlerts.evaluate(true, true, NotificationManager.IMPORTANCE_DEFAULT, true))
        assertEquals(PhoneCallAlerts.Block.FULL_SCREEN,
            PhoneCallAlerts.evaluate(true, true, NotificationManager.IMPORTANCE_HIGH, false))
    }

    @Test fun enabledHighImportanceAndFullScreenAllowsRolePreflight() {
        assertNull(PhoneCallAlerts.evaluate(
            true, true, NotificationManager.IMPORTANCE_HIGH, true))
    }

    @Test fun deniedPostRecoversThroughSettingsAfterFirstRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("phone_alerts", Context.MODE_PRIVATE)
        val original = prefs.getBoolean("post_attempted", false)
        try {
            prefs.edit().putBoolean("post_attempted", false).commit()
            assertEquals(PhoneCallAlerts.PostRoute.REQUEST, PhoneCallAlerts.nextPostRoute(context))
            assertEquals(PhoneCallAlerts.PostRoute.SETTINGS, PhoneCallAlerts.nextPostRoute(context))
        } finally {
            prefs.edit().putBoolean("post_attempted", original).commit()
        }
    }
}
