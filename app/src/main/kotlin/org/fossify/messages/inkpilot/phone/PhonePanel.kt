package org.fossify.messages.inkpilot.phone
import android.app.Activity
import android.content.Intent
import android.telecom.Call
import android.text.InputType
import android.view.View
import android.widget.EditText
import org.fossify.messages.inkpilot.ui.StudioUi

private const val PHONE_NOTE_SP = 14f

internal class PhonePanel(
    private val activity: Activity,
    private val actions: ManualDialActions = AndroidDialActions(activity),
    private val openHistory: () -> Unit = {},
    private val openCallControls: (Boolean) -> Unit = { outgoing ->
        activity.startActivity(Intent(activity, ManualCallActivity::class.java).apply {
            if (outgoing) putExtra(ManualCallActivity.OUTGOING_REQUEST, true)
        })
    },
    private val hasActiveCall: () -> Boolean = { StudioInCallService.current?.activeCalls?.any { call ->
        call.state != Call.STATE_DISCONNECTED && call.state != Call.STATE_DISCONNECTING
    } == true },
) {
    fun view(initial: String = ""): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Calls stay manual.", PHONE_NOTE_SP))
        val held = actions.held()
        body.addView(StudioUi.text(activity,
            if (held) "inkPilot is your Phone app" else "Your existing Phone app handles calls"))
        PhoneCallAlerts.blocker(activity)?.let { block ->
            body.addView(StudioUi.text(activity, block.guidance))
            body.addView(StudioUi.button(activity, block.action) { PhoneCallAlerts.address(activity) })
        }
        body.addView(StudioUi.button(activity, if (held) "Change Phone app" else "Choose inkPilot as Phone app") {
            runCatching { if (held) actions.restore() else actions.request() }
                .onFailure { StudioUi.error(activity, "Open Android Default apps settings to choose a Phone app.") }
        })
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
            else if (actions.place(value) == ManualDialResult.LOCAL_CONTROLS) openCallControls(true)
        })
        body.addView(StudioUi.button(activity, "Call history", openHistory))
        val callControls = StudioUi.button(activity, "Call controls") {
            if (actions.held() && hasActiveCall()) openCallControls(false)
            else StudioUi.error(activity, "No active inkPilot call controls are available.")
        }
        body.addView(callControls)
        val canvas = StudioUi.scroll(activity, body)
        val update = { callControls.visibility = if (actions.held() && hasActiveCall()) View.VISIBLE else View.GONE }
        update()
        canvas.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = StudioInCallService.observeCalls(update)
            override fun onViewDetachedFromWindow(view: View) = StudioInCallService.stopObservingCalls(update)
        })
        return canvas
    }
}
