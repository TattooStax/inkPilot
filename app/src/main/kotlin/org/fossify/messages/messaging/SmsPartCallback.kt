package org.fossify.messages.messaging

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Sms
import org.fossify.messages.extensions.messagingUtils

private val smsPartSettleLock = Any()

internal data class SmsPartCallbackResult(
    val verified: Boolean,
    val state: SmsPartState?,
    val rowId: Long?,
)

/** Native row presence and the durable part journal are both required before any terminal type. */
internal fun settleSmsPart(
    journal: SmsPartJournal,
    callback: SmsPartCallback,
    success: Boolean,
    rowExists: () -> Boolean,
    writeNativeType: (Int) -> Boolean,
): SmsPartCallbackResult = synchronized(smsPartSettleLock) {
    val exists = runCatching { rowExists() }.getOrDefault(false)
    if (!exists) {
        journal.unknown(callback)
        return SmsPartCallbackResult(false, SmsPartState.UNKNOWN, callback.rowId)
    }
    val state = journal.record(callback, success)
        ?: return SmsPartCallbackResult(false, null, callback.rowId)
    val target = state.nativeTargetType()
    if (target == null) return SmsPartCallbackResult(true, state, callback.rowId)
    val persisted = runCatching { writeNativeType(target) }.getOrDefault(false)
    if (!persisted) {
        journal.unknown(callback)
        // A provider update may have applied while its readback failed.
        if (target == Sms.MESSAGE_TYPE_SENT) runCatching { writeNativeType(Sms.MESSAGE_TYPE_FAILED) }
        return SmsPartCallbackResult(false, SmsPartState.UNKNOWN, callback.rowId)
    }
    val terminal = when (state) {
        SmsPartState.READY_SENT -> journal.terminal(callback, state, SmsPartState.SENT)
        SmsPartState.READY_FAILED -> journal.terminal(callback, state, SmsPartState.FAILED)
        else -> true
    }
    if (!terminal) {
        journal.unknown(callback)
        // A disputed part may have raced the final native update; never leave it as SENT.
        runCatching { writeNativeType(Sms.MESSAGE_TYPE_FAILED) }
        return SmsPartCallbackResult(false, SmsPartState.UNKNOWN, callback.rowId)
    }
    return SmsPartCallbackResult(true, when (state) {
        SmsPartState.READY_SENT -> SmsPartState.SENT
        SmsPartState.READY_FAILED -> SmsPartState.FAILED
        else -> state
    }, callback.rowId)
}

private fun SmsPartState.nativeTargetType(): Int? = when (this) {
    SmsPartState.READY_SENT -> Sms.MESSAGE_TYPE_SENT
    SmsPartState.PARTIAL_FAILED, SmsPartState.READY_FAILED, SmsPartState.UNKNOWN -> Sms.MESSAGE_TYPE_FAILED
    else -> null
}

/** Read the provider row by ID, including ID readback; a Room copy is insufficient. */
internal fun Context.exactSmsRowType(rowId: Long): Int? {
    val row = ContentUris.withAppendedId(Sms.CONTENT_URI, rowId)
    return contentResolver.query(row, arrayOf(Sms._ID, Sms.TYPE), null, null, null)?.use {
        if (it.moveToFirst() && it.getLong(0) == rowId) it.getInt(1) else null
    }
}

internal fun Context.processSmsSentPart(uri: Uri?, success: Boolean): SmsPartCallbackResult {
    val callback = SmsPartCallback.parse(uri) ?: return SmsPartCallbackResult(false, null, null)
    val row = ContentUris.withAppendedId(Sms.CONTENT_URI, callback.rowId)
    return SmsPartJournal(this).use { journal ->
        settleSmsPart(journal, callback, success,
            rowExists = { exactSmsRowType(callback.rowId) != null },
            writeNativeType = { type -> messagingUtils.updateSmsMessageSendingStatus(row, type) })
    }
}
