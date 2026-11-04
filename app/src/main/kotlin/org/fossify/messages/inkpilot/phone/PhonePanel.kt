package org.fossify.messages.inkpilot.phone
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.core.app.ActivityCompat
import org.fossify.messages.inkpilot.ui.StudioUi

internal class PhonePanel(private val activity: Activity) {
    fun view(initial: String = ""): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Manual Phone", 24f, true))
        body.addView(StudioUi.text(activity, "SIM calls are controlled by you. No AI answering or call audio capture.", 14f))
        val held = PhoneRoleSetup.held(activity)
        body.addView(StudioUi.text(activity, if (held) "inkPilot is your Phone app" else "Your existing Phone app handles calls"))
        body.addView(StudioUi.button(activity, if (held) "Restore previous Phone app" else "Choose inkPilot as Phone app") {
            runCatching { if (held) PhoneRoleSetup.restore(activity) else PhoneRoleSetup.request(activity) }
                .onFailure { StudioUi.error(activity, "Open Android Default apps settings to choose a Phone app.") }
        })
        if (held) body.addView(StudioUi.text(activity, "Roll back: choose ${PhoneRoleSetup.previous(activity)} in Default apps → Phone.", 14f))
        val number = EditText(activity).apply {
            hint = "Phone number"; contentDescription = "Phone number"
            inputType = InputType.TYPE_CLASS_PHONE; setText(initial); setTextColor(StudioUi.ink)
        }
        body.addView(number)
        body.addView(StudioUi.button(activity, "Call") {
            val value = number.text.toString().trim()
            if (value.isEmpty()) return@button
            if (!PhoneRoleSetup.held(activity)) openFallback(value)
            else if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 720)
            } else {
                // Telecom routes emergency calls through the preloaded system dialer.
                runCatching { activity.getSystemService(TelecomManager::class.java)
                    .placeCall(Uri.fromParts("tel", value, null), Bundle()) }
                    .onFailure { StudioUi.error(activity, "Call could not start. Check SIM and Phone permissions.") }
            }
        })
        body.addView(StudioUi.button(activity, "Call history") { open(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI)) })
        body.addView(StudioUi.button(activity, "Contacts") { open(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) })
        if (StudioInCallService.current != null) body.addView(StudioUi.button(activity, "Return to call") {
            activity.startActivity(Intent(activity, ManualCallActivity::class.java))
        })
        return StudioUi.scroll(activity, body)
    }
    private fun openFallback(number: String) {
        val target = activity.getSystemService(TelecomManager::class.java).defaultDialerPackage?.takeUnless { it == activity.packageName }
        if (target == null) {
            StudioUi.error(activity, "Choose a Phone app in Android Default apps settings.")
            PhoneRoleSetup.restore(activity)
        } else open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(target))
    }
    private fun open(intent: Intent) {
        runCatching { activity.startActivity(intent) }.onFailure { StudioUi.error(activity, "No app is available for this action.") }
    }
}
