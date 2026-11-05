package org.fossify.messages.inkpilot.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Seed recovery on boot or app update without opening a staff screen. */
internal class CalendarReminderRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        Thread({
            try { CalendarReminderRecovery(context.applicationContext).requestNow() }
            catch (_: Exception) { /* Existing periodic work remains available for a later wakeup. */ }
            finally { pending.finish() }
        }, "calendar-reminder-recovery").start()
    }
}
