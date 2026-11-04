package org.fossify.messages.inkpilot.phone

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.view.WindowManager
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.ui.StudioUi

class ManualCallActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    override fun onResume() {
        super.onResume()
        StudioInCallService.current?.changed = { runOnUiThread { render() } }
        handler.post(refresh)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        StudioInCallService.current?.changed = null
        setContentView(android.widget.TextView(this))
        super.onPause()
    }
    private fun render() {
        val body = StudioUi.column(this)
        val service = StudioInCallService.current
        val calls = service?.activeCalls.orEmpty()
        if (calls.isEmpty()) {
            body.addView(StudioUi.text(this, "Call ended"))
            body.addView(StudioUi.button(this, "Close") { finish() })
        }
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked || appLockManager.isLocked()
        if (locked) body.addView(StudioUi.button(this, "Unlock caller details") {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
            org.fossify.messages.inkpilot.ui.StudioAppLock(this).onResume()
        })
        calls.forEach { call ->
            body.addView(StudioUi.text(this, CallPresentation.identity(locked, call.details.handle?.schemeSpecificPart), 22f, true))
            body.addView(StudioUi.text(this, stateLabel(call.state)))
            if (call.state == Call.STATE_RINGING) {
                body.addView(StudioUi.button(this, "Answer") { call.answer(VideoProfile.STATE_AUDIO_ONLY) })
                body.addView(StudioUi.button(this, "Decline") { call.reject(false, null) })
            } else {
                body.addView(StudioUi.button(this, "End call") { call.disconnect() })
                if (call.details.can(Call.Details.CAPABILITY_HOLD)) body.addView(
                    StudioUi.button(this, if (call.state == Call.STATE_HOLDING) "Resume" else "Hold") {
                        if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
                    })
                if (!locked) addKeypad(body, call)
                if (call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE))
                    body.addView(StudioUi.button(this, "Merge calls") { call.mergeConference() })
                if (call.details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE))
                    body.addView(StudioUi.button(this, "Swap calls") { call.swapConference() })
                call.conferenceableCalls.firstOrNull()?.let { other ->
                    body.addView(StudioUi.button(this, "Join calls") { call.conference(other) })
                }
                if (call.remainingPostDialSequence.isNotEmpty()) {
                    body.addView(StudioUi.button(this, "Continue keypad sequence") { call.postDialContinue(true) })
                    body.addView(StudioUi.button(this, "Cancel keypad sequence") { call.postDialContinue(false) })
                }
            }
            if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                call.details.extras?.getParcelableArrayList<android.telecom.PhoneAccountHandle>(
                    android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS)?.forEach { account ->
                    body.addView(StudioUi.button(this, "Use SIM ${account.id}") { call.phoneAccountSelected(account, false) })
                }
            }
        }
        if (service != null && calls.isNotEmpty()) {
            val audio = service.callAudioState
            body.addView(StudioUi.button(this, if (audio?.isMuted == true) "Unmute" else "Mute") { service.setMuted(audio?.isMuted != true) })
            listOf("Earpiece" to CallAudioState.ROUTE_EARPIECE, "Speaker" to CallAudioState.ROUTE_SPEAKER,
                "Bluetooth" to CallAudioState.ROUTE_BLUETOOTH, "Wired headset" to CallAudioState.ROUTE_WIRED_HEADSET)
                .filter { (_, route) -> audio != null && audio.supportedRouteMask and route != 0 }
                .forEach { (label, route) -> body.addView(StudioUi.button(this, label) { service.setAudioRoute(route) }) }
        }
        setContentView(StudioUi.scroll(this, body))
    }
    private fun addKeypad(body: android.widget.LinearLayout, call: Call) {
        "123456789*0#".chunked(3).forEach { digits ->
            val row = android.widget.LinearLayout(this)
            digits.forEach { digit ->
                row.addView(StudioUi.button(this, digit.toString()) {
                    call.playDtmfTone(digit)
                    handler.postDelayed({ call.stopDtmfTone() }, 180)
                }, android.widget.LinearLayout.LayoutParams(0, StudioUi.dp(row, 56), 1f))
            }
            body.addView(row)
        }
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
