package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.OutboundAckEvent

/**
 * Files one bridge send's verdict under the pairing that
 * originated the send — the only way a send verdict is held.
 *
 * A send's verdict can arrive long after the send: the radio
 * answers asynchronously, and a multipart answer completes
 * only when its last part reports. The pairing can change in
 * between — a disconnect and re-pair stores a new credential
 * — and a verdict filed then would be stamped with the new
 * pairing's identity and reported to the new endpoint, where
 * its source id could finalize work it never named. So the
 * originating pairing's non-secret identity (its endpoint and
 * device id; never the secret) travels with the send, and
 * this filing checks it against the stored credential under
 * the credential guard, atomically with the hold the verdict
 * caller performs: the verdict is held only while the stored
 * credential still names the pairing that sent the work, and
 * is dropped — never re-filed elsewhere — when it does not.
 * Dropping is the honest end for such a verdict: its endpoint
 * is gone, and the brain that dispatched the reply still
 * holds it un-acked under the old pairing.
 *
 * Returns whether the verdict is pending in the stored queue
 * when the call returns.
 */
internal fun Context.fileOutboundVerdict(
    endpointBase: String,
    deviceId: String,
    sourceId: String,
    event: OutboundAckEvent,
    errorCode: Int?,
    reportedAtMillis: Long
): Boolean {
    return synchronized(CredentialGuard.lock) {
        val current = storedDeviceCredential()
        if (current == null || current.endpointBase != endpointBase || current.deviceId != deviceId) {
            false
        } else {
            holdAndDrainOutboundVerdict(sourceId, event, errorCode, reportedAtMillis)
        }
    }
}
