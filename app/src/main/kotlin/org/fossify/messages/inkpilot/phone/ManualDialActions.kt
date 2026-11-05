package org.fossify.messages.inkpilot.phone

import android.Manifest
import android.app.Activity
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
    fun place(number: String)
    fun unavailable()
}
internal class AndroidDialActions(
    private val activity: Activity,
    private val report: (String) -> Unit = { StudioUi.error(activity, it) },
) : ManualDialActions {
    override fun held() = PhoneRoleSetup.held(activity)
    override fun request() = PhoneRoleSetup.request(activity)
    override fun restore() = PhoneRoleSetup.restore(activity)
    override fun permitted() = activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    override fun requestPermission() = ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 720)
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
        report("Choose inkPilot as your Phone app before calling.")
    }
}
