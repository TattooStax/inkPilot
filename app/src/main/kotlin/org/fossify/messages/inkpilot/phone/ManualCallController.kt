package org.fossify.messages.inkpilot.phone

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile

internal data class ManualCallState(
    val id: String, val number: String?, val state: Int, val hold: Boolean = false,
    val merge: Boolean = false, val swap: Boolean = false, val join: Boolean = false,
    val postDial: Boolean = false, val accounts: List<String> = emptyList(),
)
internal interface ManualCallController {
    fun calls(): List<ManualCallState>
    fun muted(): Boolean
    fun routes(): List<Pair<String, Int>>
    fun dispatch(id: String, action: String, argument: String = "")
}

internal class TelecomCallController(private val service: StudioInCallService) : ManualCallController {
    private fun id(call: Call) = System.identityHashCode(call).toString()
    private fun accounts(call: Call) = call.details.extras
        ?.getParcelableArrayList<android.telecom.PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS).orEmpty()
    override fun calls() = service.activeCalls.map { call ->
        ManualCallState(id(call), call.details.handle?.schemeSpecificPart, call.state,
            call.details.can(Call.Details.CAPABILITY_HOLD),
            call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE),
            call.details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE), call.conferenceableCalls.isNotEmpty(),
            call.remainingPostDialSequence.isNotEmpty(), accounts(call).map { it.id })
    }
    override fun muted() = service.callAudioState?.isMuted == true
    override fun routes(): List<Pair<String, Int>> {
        val mask = service.callAudioState?.supportedRouteMask ?: 0
        return listOf("Earpiece" to CallAudioState.ROUTE_EARPIECE, "Speaker" to CallAudioState.ROUTE_SPEAKER,
            "Bluetooth" to CallAudioState.ROUTE_BLUETOOTH, "Wired headset" to CallAudioState.ROUTE_WIRED_HEADSET)
            .filter { (_, route) -> mask and route != 0 }
    }
    override fun dispatch(id: String, action: String, argument: String) {
        if (action == "mute") { service.setMuted(!muted()); return }
        if (action == "route") { service.setAudioRoute(argument.toInt()); return }
        val call = service.activeCalls.firstOrNull { id(it) == id } ?: return
        when (action) {
            "answer" -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
            "decline" -> call.reject(false, null)
            "end" -> call.disconnect()
            "hold" -> if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
            "merge" -> call.mergeConference()
            "swap" -> call.swapConference()
            "join" -> call.conferenceableCalls.firstOrNull()?.let(call::conference)
            "continue" -> call.postDialContinue(true)
            "cancel" -> call.postDialContinue(false)
            "sim" -> accounts(call).firstOrNull { it.id == argument }?.let { call.phoneAccountSelected(it, false) }
            "digit" -> {
                call.playDtmfTone(argument.single())
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ call.stopDtmfTone() }, 180)
            }
        }
    }
}
