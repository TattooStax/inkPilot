package org.fossify.messages.inkpilot.phone

import android.app.Activity
import android.telecom.Call
import android.widget.TextView
import org.fossify.messages.inkpilot.ui.StudioUi

/** Retains the ScrollView and each keyed control across ticks and Telecom callbacks. */
internal class ManualCallScreen(private val activity: Activity, private val unlock: () -> Unit, private val close: () -> Unit) {
    private val body = StudioUi.column(activity)
    val view = StudioUi.scroll(activity, body)
    private val rows = linkedMapOf<String, TextView>()
    private var position = 0
    private val wanted = mutableSetOf<String>()

    fun update(controller: ManualCallController?, locked: Boolean) {
        position = 0; wanted.clear()
        val calls = controller?.calls().orEmpty()
        if (calls.isEmpty()) {
            row("ended", "Call ended")
            row("close", "Close", close)
        }
        if (locked && calls.isNotEmpty()) row("unlock", "Unlock caller details", unlock)
        calls.forEach { call ->
            val prefix = call.id + ":"
            row(prefix + "identity", CallPresentation.identity(locked, call.number))
            row(prefix + "state", stateLabel(call.state))
            fun button(action: String, label: String, argument: String = "") {
                row(prefix + action + argument, label) { controller?.dispatch(call.id, action, argument) }
            }
            if (call.state == Call.STATE_RINGING) {
                button("answer", "Answer"); button("decline", "Decline")
            } else {
                button("end", "End call")
                if (call.hold) button("hold", if (call.state == Call.STATE_HOLDING) "Resume" else "Hold")
                if (!locked) "123456789*0#".forEach { button("digit", it.toString(), it.toString()) }
                if (call.merge) button("merge", "Merge calls")
                if (call.swap) button("swap", "Swap calls")
                if (call.join) button("join", "Join calls")
                if (call.postDial) { button("continue", "Continue keypad sequence"); button("cancel", "Cancel keypad sequence") }
            }
            if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT)
                call.accounts.forEachIndexed { index, account ->
                    button("sim", if (locked) "Use SIM ${index + 1}" else "Use SIM $account", account)
                }
        }
        if (controller != null && calls.isNotEmpty()) {
            row("mute", if (controller.muted()) "Unmute" else "Mute") { controller.dispatch("", "mute") }
            controller.routes().forEach { (label, route) ->
                row("route:" + route, label) { controller.dispatch("", "route", route.toString()) }
            }
        }
        rows.keys.filter { it !in wanted }.toList().forEach { key -> body.removeView(rows.remove(key)) }
    }

    private fun row(key: String, label: String, action: (() -> Unit)? = null) {
        wanted.add(key)
        val item = rows.getOrPut(key) {
            (if (action == null) StudioUi.text(activity, label, 18f) else StudioUi.button(activity, label) {}).apply { tag = key }
        }
        if (item.text.toString() != label) item.text = label
        if (action != null) item.setOnClickListener { action() }
        if (body.indexOfChild(item) != position) {
            body.removeView(item)
            body.addView(item, position)
        }
        position++
    }
    private fun stateLabel(state: Int) = when (state) {
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_ACTIVE -> "Connected"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
        Call.STATE_DISCONNECTING -> "Ending…"
        Call.STATE_DISCONNECTED -> "Call ended"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
        else -> "Phone call"
    }
}
