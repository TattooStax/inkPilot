package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.DeviceCredential

/** Caller holds OutboundSendProgressGuard.lock. Never reissue an uncertain radio call. */
internal fun Context.alreadyDispatched(credential: DeviceCredential, sourceId: String): Boolean {
    val stem = progressKeyStem(credential.endpointBase, credential.deviceId, sourceId)
    val prefs = progressPrefs()
    if (prefs.getBoolean("${stem}_dispatched", false)) return true
    // Adopt progress and pending reports written by older versions before this marker existed.
    val prior = prefs.contains("${stem}_parts") || storedPendingOutboundReportQueue().any { it.sourceId == sourceId }
    if (prior) prefs.edit().putBoolean("${stem}_dispatched", true).commit()
    return prior
}
