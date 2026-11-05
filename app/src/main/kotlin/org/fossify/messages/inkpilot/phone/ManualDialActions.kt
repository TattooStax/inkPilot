package org.fossify.messages.inkpilot.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import org.fossify.messages.inkpilot.ui.StudioUi

/** A replaceable boundary so UI tests never invoke Telecom, roles or carrier calls. */
internal interface ManualDialActions {
    fun held(): Boolean
    fun request()
    fun restore()
    fun permitted(): Boolean
    fun requestPermission()
    fun place(number: String): ManualDialResult
    fun unavailable()
}
internal enum class ManualDialResult { LOCAL_CONTROLS, SYSTEM_HANDOFF, FAILED }

internal class AndroidDialActions(
    private val activity: Activity,
    private val report: (String) -> Unit = { StudioUi.error(activity, it) },
) : ManualDialActions {
    override fun held() = PhoneRoleSetup.held(activity)
    override fun request() = PhoneRoleSetup.request(activity)
    override fun restore() = PhoneRoleSetup.restore(activity)
    override fun permitted() = activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    override fun requestPermission() = ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 720)
    override fun place(number: String): ManualDialResult {
        if (!held()) { unavailable(); return ManualDialResult.FAILED }
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            return ManualDialResult.FAILED
        }
        // An emergency call belongs to the preloaded dialer. Unknown classification also
        // leaves the system surface in charge instead of covering it with our Activity.
        val nonEmergency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            !activity.getSystemService(TelephonyManager::class.java)
                .isEmergencyNumber(PhoneNumberUtils.stripSeparators(number))
        }.getOrNull() == true else false
        // Includes emergencies: Telecom must route to the preloaded system dialer.
        return runCatching {
            activity.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            if (nonEmergency) ManualDialResult.LOCAL_CONTROLS else ManualDialResult.SYSTEM_HANDOFF
        }.getOrElse {
            StudioUi.error(activity, "Call could not start. Check SIM and Phone permissions.")
            ManualDialResult.FAILED
        }
    }
    override fun unavailable() {
        report("Choose inkPilot as your Phone app before calling.")
    }
}
