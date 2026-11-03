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

/** What one part's report does to a send's progress. */
sealed interface OutboundSendProgressUpdate {
    /** The send is still waiting on parts that have not reported. */
    data object Pending : OutboundSendProgressUpdate

    /**
     * Every part has now reported, so the send's one verdict is
     * reached: [event] is the verdict, and [errorCode] is the
     * first reported failure's sending error code on a failure.
     * The progress entry is already cleared — the verdict is
     * the caller's to file, exactly once.
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
 * The key stem is the source id's base64url spelling, so any
 * source id the wire admits keys exactly one entry. Every
 * write is one checked synchronous commit that reports
 * success only after a verifying read-back, in the shape the
 * track's other stores use. Every function here runs under
 * [OutboundSendProgressGuard] — the sender opens a send's
 * progress under it before submitting, and each part report
 * folds under it.
 */

private fun progressKeyStem(sourceId: String): String {
    return Base64.encodeToString(
        sourceId.toByteArray(Charsets.UTF_8),
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
internal fun Context.beginOutboundSendProgress(sourceId: String, partCount: Int): Boolean {
    val stem = progressKeyStem(sourceId)
    val mask = PART_PENDING.toString().repeat(partCount)
    val committed = progressPrefs().edit()
        .putInt("${stem}_parts", partCount)
        .putString("${stem}_mask", mask)
        .remove("${stem}_error")
        .commit()
    if (!committed) {
        return false
    }
    val prefs = progressPrefs()
    return prefs.getInt("${stem}_parts", 0) == partCount &&
        prefs.getString("${stem}_mask", null) == mask &&
        !prefs.contains("${stem}_error")
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
 * entry is cleared in the same commit and the update is the
 * send's verdict: a failure if any part failed, carrying the
 * first reported failure's error code, else sent.
 */
internal fun Context.recordOutboundSendPart(
    sourceId: String,
    partIndex: Int,
    succeeded: Boolean,
    errorCode: Int?
): OutboundSendProgressUpdate {
    val stem = progressKeyStem(sourceId)
    val prefs = progressPrefs()
    val entry = progressEntry(prefs, stem, partIndex) ?: return OutboundSendProgressUpdate.Unknown
    if (entry.alreadyReported) {
        return if (entry.stillPending) {
            OutboundSendProgressUpdate.Pending
        } else {
            OutboundSendProgressUpdate.Unknown
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
    val stillPending: Boolean
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
    return ProgressEntry(
        mask = mask,
        storedError = storedError,
        alreadyReported = mask[partIndex] != PART_PENDING,
        stillPending = PART_PENDING in mask
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
 * Closes a completed progress: the entry is cleared in the
 * same commit that records the final fold, so the verdict is
 * produced exactly once, and the update is that verdict — a
 * failure if any part failed, else sent. A clear that cannot
 * be verified reads as Unknown instead: the caller files
 * nothing, because filing a verdict whose progress might
 * produce it again would double-file it.
 */
private fun completeProgress(
    prefs: SharedPreferences,
    stem: String,
    newMask: String,
    firstError: Int?
): OutboundSendProgressUpdate {
    val committed = prefs.edit()
        .putString("${stem}_mask", newMask)
        .remove("${stem}_parts")
        .remove("${stem}_mask")
        .remove("${stem}_error")
        .commit()
    if (!committed || prefs.contains("${stem}_parts") || prefs.contains("${stem}_mask")) {
        return OutboundSendProgressUpdate.Unknown
    }
    return if (PART_FAILED in newMask) {
        OutboundSendProgressUpdate.Completed(OutboundAckEvent.PHONE_REPORTED_FAILURE, firstError)
    } else {
        OutboundSendProgressUpdate.Completed(OutboundAckEvent.PHONE_ACKED_SENT, null)
    }
}

/** Clears a send's progress, for a submission that never went out. */
internal fun Context.clearOutboundSendProgress(sourceId: String) {
    val stem = progressKeyStem(sourceId)
    progressPrefs().edit()
        .remove("${stem}_parts")
        .remove("${stem}_mask")
        .remove("${stem}_error")
        .commit()
}
