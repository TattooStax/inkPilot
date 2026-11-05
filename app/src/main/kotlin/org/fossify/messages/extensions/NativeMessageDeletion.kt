package org.fossify.messages.extensions

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.commons.extensions.showErrorToast
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin

/** A native deletion is authoritative; an unverified result leaves the local copy intact. */
internal fun deleteProviderRowThenCache(
    roleHeld: Boolean,
    deleteProvider: () -> Int,
    providerRowExists: () -> Boolean?,
    deleteCache: () -> Unit,
): Boolean {
    if (!roleHeld || deleteProvider() <= 0 || providerRowExists() != false) return false
    deleteCache()
    return true
}

/** Return true only when the exact provider row is gone and the local cache is updated. */
@Suppress("TooGenericExceptionCaught") // Provider failures preserve local attention.
fun Context.deleteMessage(id: Long, isMMS: Boolean): Boolean {
    if (id <= 0L) return false
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val row = ContentUris.withAppendedId(uri, id)
    return try {
        deleteProviderRowThenCache(
            roleHeld = SmsRole.isHeld(this),
            deleteProvider = { contentResolver.delete(uri, "${Sms._ID} = ?", arrayOf(id.toString())) },
            providerRowExists = {
                contentResolver.query(row, arrayOf(Sms._ID), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) false
                    else if (cursor.getLong(0) == id) true else null
                }
            },
            deleteCache = {
                messagesDB.delete(MessageKey(if (isMMS) MessageOrigin.MMS else MessageOrigin.SMS, id))
            },
        )
    } catch (e: Exception) {
        showErrorToast(e)
        false
    }
}
