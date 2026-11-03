package org.fossify.messages.transport

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.TransportOutcome

private const val OUTBOUND_SEND_PROGRESS_PREFS = "outbound_send_progress"
private const val PART_PENDING = 'P'
private const val PART_SENT = 'S'
private const val PART_FAILED = 'F'
private const val VERDICT_SENT = 'S'
private const val VERDICT_FAILED = 'F'

/**
 * One completed send's retained verdict: the pairing that
 * sent the work, the work's source id, and the verdict the
 * parts reached, recovered from a done progress entry so it
 * can be filed after an interruption.
 */
data class CompletedOutboundVerdict(
    val endpointBase: String,
    val deviceId: String,
    val sourceId: String,
    val event: OutboundAckEvent,
    val errorCode: Int?
)

/** What one part's report does to a send's progress. */
sealed interface OutboundSendProgressUpdate {
    /** The send is still waiting on parts that have not reported. */
    data object Pending : OutboundSendProgressUpdate

    /**
     * Every part has now reported, so the send's one verdict is
     * reached: [event] is the verdict, and [errorCode] is the
     * first reported failure's sending error code on a failure.
     * The verdict stays stored in the progress entry until the
     * caller clears it after the verdict is settled, and a
     * repeat part report returns this same update again — the
     * re-drive that lets a retained verdict be filed later.
     */
    data class Completed(val event: OutboundAckEvent, val errorCode: Int?) : OutboundSendProgressUpdate

    /**
     * No progress names this send — it never opened, already
     * completed and was cleared, or names a part outside the
     * send. A part report with nothing to fold into files
     * nothing: a verdict reconstructed from a fragment would
     * be a guess, not the phone's verdict.
     */
    data object Unknown : OutboundSendProgressUpdate
}

/**
 * Durable progress for multipart bridge sends, in the app's
 * shared preferences: how many parts a send has, which parts
 * have reported and how, and the first reported failure's
 * error code. A send's parts report through separate
 * broadcasts that can arrive in any order and after the
 * process that submitted the send is gone, so the aggregation
 * cannot live in memory — and the verdict is filed only when
 * every part has reported, because one part's result is that
 * part's outcome, never the whole message's: an earlier part
 * can fail while the final part succeeds, and reporting
 * success then would ack a reply that never fully left.
 *
 * The key stem is the base64url spelling of the originating
 * pairing's identity (endpoint and device id) together with
 * the source id, so one source id sent under two pairings
 * keys two entries and neither send's reports can fold into
 * the other's progress. Every write is one checked
 * synchronous commit that reports success only after a
 * verifying read-back, in the shape the track's other stores
 * use. Every function here runs under
 * [OutboundSendProgressGuard] — the sender opens a send's
 * progress under it before submitting, and each part report
 * folds under it.
 *
 * A completed send's verdict stays in its entry — marked
 * done, with its event and error code — until the caller
 * clears it, which it does only once the verdict is settled:
 * durably held in the pending outbound report queue, or
 * deliberately dropped because its pairing is gone. Clearing
 * at completion would delete the only durable record before
 * the verdict is held anywhere, and a hold that failed (or a
 * process that died first) would lose a verdict the drain
 * could otherwise still report.
 */

private fun progressKeyStem(endpointBase: String, deviceId: String, sourceId: String): String {
    val identity = "$endpointBase\n$deviceId\n$sourceId"
    return Base64.encodeToString(
        identity.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

private fun Context.progressPrefs() = getSharedPreferences(OUTBOUND_SEND_PROGRESS_PREFS, Context.MODE_PRIVATE)

/**
 * Opens a send's progress: [partCount] parts, none reported.
 * Any earlier entry under the same source id is replaced —
 * a source id names one send, and a re-opened progress is the
 * new send's. Returns whether the opened progress read back
 * as stored.
 */
internal fun Context.beginOutboundSendProgress(
    endpointBase: String,
    deviceId: String,
    sourceId: String,
    partCount: Int
): Boolean {
    val stem = progressKeyStem(endpointBase, deviceId, sourceId)
    val mask = PART_PENDING.toString().repeat(partCount)
    val committed = progressPrefs().edit()
        .putInt("${stem}_parts", partCount)
        .putString("${stem}_mask", mask)
        .putString("${stem}_endpoint", endpointBase)
        .putString("${stem}_device", deviceId)
        .putString("${stem}_source", sourceId)
        .remove("${stem}_error")
        .remove("${stem}_done")
        .remove("${stem}_event")
        .commit()
    if (!committed) {
        return false
    }
    val prefs = progressPrefs()
    return prefs.getInt("${stem}_parts", 0) == partCount &&
        prefs.getString("${stem}_mask", null) == mask &&
        prefs.getString("${stem}_endpoint", null) == endpointBase &&
        prefs.getString("${stem}_device", null) == deviceId &&
        prefs.getString("${stem}_source", null) == sourceId &&
        !prefs.contains("${stem}_error") &&
        !prefs.contains("${stem}_done")
}

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
 * report folds under.
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
    return CompletedOutboundVerdict(endpointBase, deviceId, sourceId, event, errorCode)
}

/**
 * Folds one part's report into a send's progress. A failure
 * report carries its sending error code; one without a code
 * is recorded under the unknown-error code, because a failure
 * verdict the report wire would carry always names a code.
 * A part that already reported keeps its first result — the
 * platform reports each part once, and a repeat broadcast is
 * the same report arriving twice, not a second chance at the
 * verdict. When the fold leaves every part reported, the
 * verdict is stored in the entry (marked done) and returned;
 * it stays stored until the caller clears it after filing,
 * and a repeat report against a done entry returns the same
 * verdict again instead of folding, so a verdict whose
 * filing could not be settled can be re-driven by the next
 * report that names the send.
 */
internal fun Context.recordOutboundSendPart(
    endpointBase: String,
    deviceId: String,
    sourceId: String,
    partIndex: Int,
    succeeded: Boolean,
    errorCode: Int?
): OutboundSendProgressUpdate {
    val stem = progressKeyStem(endpointBase, deviceId, sourceId)
    val prefs = progressPrefs()
    val entry = progressEntry(prefs, stem, partIndex) ?: return OutboundSendProgressUpdate.Unknown
    val doneEvent = entry.doneEvent
    if (doneEvent != null) {
        return OutboundSendProgressUpdate.Completed(
            doneEvent,
            if (doneEvent == OutboundAckEvent.PHONE_REPORTED_FAILURE) entry.storedError else null
        )
    }
    if (entry.alreadyReported) {
        return if (entry.stillPending) {
            OutboundSendProgressUpdate.Pending
        } else {
            completeProgress(prefs, stem, entry.mask, entry.storedError)
        }
    }
    val newMask = StringBuilder(entry.mask).apply {
        setCharAt(partIndex, if (succeeded) PART_SENT else PART_FAILED)
    }.toString()
    val firstError = entry.storedError ?: if (succeeded) null else errorCode ?: TransportOutcome.UNKNOWN_ERROR
    return if (PART_PENDING in newMask) {
        if (storeProgressMask(prefs, stem, newMask, entry.storedError, firstError)) {
            OutboundSendProgressUpdate.Pending
        } else {
            OutboundSendProgressUpdate.Unknown
        }
    } else {
        completeProgress(prefs, stem, newMask, firstError)
    }
}

/** One send's stored progress, read for one part's fold. */
private data class ProgressEntry(
    val mask: String,
    val storedError: Int?,
    val alreadyReported: Boolean,
    val stillPending: Boolean,
    val doneEvent: OutboundAckEvent?
)

/**
 * Reads the progress a part report folds into, or null when
 * no well-formed entry names that part: no entry at all, a
 * part count that disagrees with its mask, or a part outside
 * the send.
 */
private fun progressEntry(prefs: SharedPreferences, stem: String, partIndex: Int): ProgressEntry? {
    val partCount = prefs.getInt("${stem}_parts", 0)
    val mask = prefs.getString("${stem}_mask", null) ?: return null
    if (partCount < 1 || mask.length != partCount || partIndex !in mask.indices) {
        return null
    }
    val storedError = if (prefs.contains("${stem}_error")) prefs.getInt("${stem}_error", 0) else null
    val doneEvent = if (prefs.getBoolean("${stem}_done", false)) {
        when (prefs.getString("${stem}_event", null)) {
            VERDICT_SENT.toString() -> OutboundAckEvent.PHONE_ACKED_SENT
            VERDICT_FAILED.toString() -> OutboundAckEvent.PHONE_REPORTED_FAILURE
            else -> null
        }
    } else {
        null
    }
    return ProgressEntry(
        mask = mask,
        storedError = storedError,
        alreadyReported = mask[partIndex] != PART_PENDING,
        stillPending = PART_PENDING in mask,
        doneEvent = doneEvent
    )
}

/**
 * Stores a still-incomplete progress after one part's fold,
 * recording the first reported failure's code alongside it.
 * Returns whether the stored progress read back as written.
 */
private fun storeProgressMask(
    prefs: SharedPreferences,
    stem: String,
    newMask: String,
    storedError: Int?,
    firstError: Int?
): Boolean {
    val editor = prefs.edit().putString("${stem}_mask", newMask)
    if (storedError == null && firstError != null) {
        editor.putInt("${stem}_error", firstError)
    }
    if (!editor.commit()) {
        return false
    }
    return prefs.getString("${stem}_mask", null) == newMask
}

/**
 * Closes a completed progress: the final fold and the
 * verdict it reaches are stored in the same commit, the
 * entry marked done with its event — a failure if any part
 * failed, carrying the first reported failure's code, else
 * sent. The entry is NOT cleared here: it is cleared by the
 * caller once the verdict is settled, so a verdict that
 * could not be filed stays recoverable in the entry and a
 * repeat part report returns it again. A store that cannot
 * be verified reads as Unknown instead: the caller files
 * nothing, because filing a verdict whose completion might
 * not be durable could lose it silently at the next fold.
 */
private fun completeProgress(
    prefs: SharedPreferences,
    stem: String,
    newMask: String,
    firstError: Int?
): OutboundSendProgressUpdate {
    val failed = PART_FAILED in newMask
    val eventChar = if (failed) VERDICT_FAILED else VERDICT_SENT
    val editor = prefs.edit()
        .putString("${stem}_mask", newMask)
        .putBoolean("${stem}_done", true)
        .putString("${stem}_event", eventChar.toString())
    if (failed && firstError != null) {
        editor.putInt("${stem}_error", firstError)
    } else if (!failed) {
        editor.remove("${stem}_error")
    }
    if (!editor.commit()) {
        return OutboundSendProgressUpdate.Unknown
    }
    val verified = prefs.getString("${stem}_mask", null) == newMask &&
        prefs.getBoolean("${stem}_done", false) &&
        prefs.getString("${stem}_event", null) == eventChar.toString()
    if (!verified) {
        return OutboundSendProgressUpdate.Unknown
    }
    return if (failed) {
        OutboundSendProgressUpdate.Completed(OutboundAckEvent.PHONE_REPORTED_FAILURE, firstError)
    } else {
        OutboundSendProgressUpdate.Completed(OutboundAckEvent.PHONE_ACKED_SENT, null)
    }
}

/** Clears a send's progress, for a submission that never went out. */
internal fun Context.clearOutboundSendProgress(endpointBase: String, deviceId: String, sourceId: String) {
    val stem = progressKeyStem(endpointBase, deviceId, sourceId)
    progressPrefs().edit()
        .remove("${stem}_parts")
        .remove("${stem}_mask")
        .remove("${stem}_endpoint")
        .remove("${stem}_device")
        .remove("${stem}_source")
        .remove("${stem}_error")
        .remove("${stem}_done")
        .remove("${stem}_event")
        .commit()
}
