package org.fossify.messages.transport

import android.content.Context
import android.content.SharedPreferences
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.TransportOutcome


/**
 * Every completed send's retained verdict, read back from
 * the done entries: the recovery the drain path files
 * before draining, for verdicts whose filing was
 * interrupted — a hold that was not durable, or a process
 * that died between completion and filing — and that no
 * later part report can be relied on to re-drive, because
 * at completion every part has already reported. A done
 * entry whose stored identity or event is damaged names
 * no verdict and is skipped, never guessed at; a failure
 * whose error code was lost is recovered under the
 * unknown-error code, the same rule a code-less failure
 * report folds under. The verdict carries the time stored
 * with its completion — the time the phone reached it —
 * so a recovered report names when the send was decided,
 * not when the recovery happened to run; an entry written
 * before verdict times were stored recovers under the
 * read time, the only time it can still name.
 */
internal fun Context.storedCompletedOutboundVerdicts(): List<CompletedOutboundVerdict> {
    val prefs = progressPrefs()
    val doneStems = prefs.all.keys
        .filter { it.endsWith("_done") && prefs.getBoolean(it, false) }
        .map { it.removeSuffix("_done") }
    return doneStems.mapNotNull { stem -> completedVerdict(prefs, stem) }
}

/** One done entry read back as its verdict, or null when its stored identity or event names none. */
private fun completedVerdict(prefs: SharedPreferences, stem: String): CompletedOutboundVerdict? {
    val endpointBase = prefs.getString("${stem}_endpoint", null)?.takeIf { it.isNotBlank() } ?: return null
    val deviceId = prefs.getString("${stem}_device", null)?.takeIf { it.isNotBlank() } ?: return null
    val sourceId = prefs.getString("${stem}_source", null)?.takeIf { it.isNotBlank() } ?: return null
    val event = when (prefs.getString("${stem}_event", null)) {
        VERDICT_SENT.toString() -> OutboundAckEvent.PHONE_ACKED_SENT
        VERDICT_FAILED.toString() -> OutboundAckEvent.PHONE_REPORTED_FAILURE
        else -> return null
    }
    val errorCode = if (event == OutboundAckEvent.PHONE_REPORTED_FAILURE) {
        if (prefs.contains("${stem}_error")) {
            prefs.getInt("${stem}_error", TransportOutcome.UNKNOWN_ERROR)
        } else {
            TransportOutcome.UNKNOWN_ERROR
        }
    } else {
        null
    }
    val reportedAtMillis = if (prefs.contains("${stem}_reported_at")) {
        prefs.getLong("${stem}_reported_at", 0L)
    } else {
        System.currentTimeMillis()
    }
    return CompletedOutboundVerdict(endpointBase, deviceId, sourceId, event, errorCode, reportedAtMillis)
}

/**
 * Stores a verdict the phone reached without any part
 * report — a submission that failed synchronously, which
 * no callback follows — as a completed entry under the
 * send's identity, replacing any progress the attempt
 * opened: the verdict is retained in the same shape a
 * completed fold leaves, so the drain's recovery files it
 * (and clears it once settled) exactly as it files a
 * part-reported verdict, and a late part report folds into
 * the done entry and re-drives this same verdict instead
 * of replacing it — a submission that threw is reported
 * failed, whatever a stray callback later claims. Returns
 * whether the stored verdict read back as written.
 */
internal fun Context.storeCompletedOutboundVerdict(
    endpointBase: String,
    deviceId: String,
    sourceId: String,
    event: OutboundAckEvent,
    errorCode: Int?,
    reportedAtMillis: Long
): Boolean {
    val stem = progressKeyStem(endpointBase, deviceId, sourceId)
    val eventChar = if (event == OutboundAckEvent.PHONE_REPORTED_FAILURE) VERDICT_FAILED else VERDICT_SENT
    val editor = progressPrefs().edit()
        .putInt("${stem}_parts", 1)
        .putString(
            "${stem}_mask",
            if (event == OutboundAckEvent.PHONE_REPORTED_FAILURE) PART_FAILED.toString() else PART_SENT.toString()
        )
        .putString("${stem}_endpoint", endpointBase)
        .putString("${stem}_device", deviceId)
        .putString("${stem}_source", sourceId)
        .putBoolean("${stem}_done", true)
        .putString("${stem}_event", eventChar.toString())
        .putLong("${stem}_reported_at", reportedAtMillis)
    if (event == OutboundAckEvent.PHONE_REPORTED_FAILURE && errorCode != null) {
        editor.putInt("${stem}_error", errorCode)
    } else {
        editor.remove("${stem}_error")
    }
    if (!editor.commit()) {
        return false
    }
    val prefs = progressPrefs()
    return prefs.getBoolean("${stem}_done", false) &&
        prefs.getString("${stem}_event", null) == eventChar.toString() &&
        prefs.getLong("${stem}_reported_at", 0L) == reportedAtMillis
}

