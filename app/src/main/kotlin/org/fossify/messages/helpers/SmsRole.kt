package org.fossify.messages.helpers

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony

/** RoleManager is authoritative; legacy SMS discovery can reject permission-managed app-ops. */
object SmsRole {
    fun isHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(RoleManager::class.java) ?: return false
            return manager.isRoleAvailable(RoleManager.ROLE_SMS) && manager.isRoleHeld(RoleManager.ROLE_SMS)
        }
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
}
