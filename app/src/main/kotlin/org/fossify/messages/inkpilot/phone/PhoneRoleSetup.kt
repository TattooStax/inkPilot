package org.fossify.messages.inkpilot.phone
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager

internal object PhoneRoleSetup {
    fun held(context: Context) = context.getSystemService(TelecomManager::class.java).defaultDialerPackage == context.packageName
    fun request(activity: Activity) {
        if (!PhoneCallAlerts.ensure(activity)) return
        val telecom = activity.getSystemService(TelecomManager::class.java)
        activity.getSharedPreferences("phone_role", 0).edit().putString("previous", telecom.defaultDialerPackage).apply()
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (!roles.isRoleAvailable(RoleManager.ROLE_DIALER)) return
            roles.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
        activity.startActivity(intent)
    }
    fun restore(activity: Activity) = activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    fun previous(context: Context) = context.getSharedPreferences("phone_role", 0).getString("previous", null) ?: "your previous Phone app"
}
