package org.fossify.messages.inkpilot.phone
import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import org.fossify.messages.inkpilot.ui.StudioUi

internal class PhonePanel(
    private val activity: Activity,
    private val actions: ManualDialActions = AndroidDialActions(activity),
    private val openHistory: () -> Unit = {},
    private val openContacts: () -> Unit = {},
) {
    fun view(initial: String = ""): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Manual Phone", 24f, true))
        body.addView(StudioUi.text(activity, "SIM calls are controlled by you. No AI answering or call audio capture.", 14f))
        val held = actions.held()
        body.addView(StudioUi.text(activity, if (held) "inkPilot is your Phone app" else "Your existing Phone app handles calls"))
        PhoneCallAlerts.blocker(activity)?.let { block ->
            body.addView(StudioUi.text(activity, block.guidance))
            body.addView(StudioUi.button(activity, block.action) { PhoneCallAlerts.address(activity) })
        }
        body.addView(StudioUi.button(activity, if (held) "Restore previous Phone app" else "Choose inkPilot as Phone app") {
            runCatching { if (held) actions.restore() else actions.request() }
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
            if (!actions.held()) actions.unavailable()
            else if (!actions.permitted()) actions.requestPermission()
            else actions.place(value)
        })
        body.addView(StudioUi.button(activity, "Call history", openHistory))
        body.addView(StudioUi.button(activity, "Contacts", openContacts))
        if (StudioInCallService.current?.activeCalls?.isNotEmpty() == true) {
            body.addView(StudioUi.button(activity, "Return to call") {
                activity.startActivity(Intent(activity, ManualCallActivity::class.java))
            })
        }
        return StudioUi.scroll(activity, body)
    }
}
