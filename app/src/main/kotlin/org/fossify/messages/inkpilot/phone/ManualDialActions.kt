package org.fossify.messages.inkpilot.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.app.ActivityCompat
import org.fossify.messages.inkpilot.ui.StudioUi

/** A replaceable boundary so UI tests never invoke Telecom, roles or carrier calls. */
internal interface ManualDialActions {
    fun held(): Boolean
    fun request()
    fun restore()
    fun permitted(): Boolean
    fun requestPermission()
    fun fallbackPackage(): String?
    fun openFallback(target: String, number: String)
    fun place(number: String)
    fun unavailable()
}
internal class AndroidDialActions(
    private val activity: Activity,
    private val launch: (Intent) -> Unit = { activity.startActivity(it) },
    private val report: (String) -> Unit = { StudioUi.error(activity, it) },
) : ManualDialActions {
    override fun held() = PhoneRoleSetup.held(activity)
    override fun request() = PhoneRoleSetup.request(activity)
    override fun restore() = PhoneRoleSetup.restore(activity)
    override fun permitted() = activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    override fun requestPermission() = ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 720)
    override fun fallbackPackage() = activity.getSystemService(TelecomManager::class.java).defaultDialerPackage
    override fun openFallback(target: String, number: String) {
        runCatching { launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(target)) }
            .onFailure { report("No phone dialer is available.") }
    }
    override fun place(number: String) {
        if (!held()) { unavailable(); return }
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            return
        }
        // Includes emergencies: Telecom must route to the preloaded system dialer.
        runCatching { activity.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle()) }
            .onFailure { StudioUi.error(activity, "Call could not start. Check SIM and Phone permissions.") }
    }
    override fun unavailable() {
        StudioUi.error(activity, "Choose a Phone app in Android Default apps settings.")
        restore()
    }
}
