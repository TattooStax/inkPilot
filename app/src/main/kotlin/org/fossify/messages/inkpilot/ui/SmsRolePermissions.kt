package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Request the permissions used by the in-shell SMS experience after the role flow. */
internal object SmsRolePermissions {
    fun request(activity: Activity, requestCode: Int, onReady: () -> Unit) {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) activity.requestPermissions(needed.toTypedArray(), requestCode) else onReady()
    }
}
