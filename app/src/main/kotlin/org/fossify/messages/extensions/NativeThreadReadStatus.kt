package org.fossify.messages.extensions

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.messages.helpers.SmsRole

/** A failed or unavailable provider readback cannot clear the app's unread attention. */
internal fun markThreadReadAfterNative(
    roleHeld: Boolean,
    updateNative: () -> Unit,
    smsHasUnread: () -> Boolean?,
    mmsHasUnread: () -> Boolean?,
    markLocal: () -> Unit,
): Boolean {
    if (!roleHeld) return false
    return runCatching {
        updateNative()
        if (smsHasUnread() != false || mmsHasUnread() != false) false
        else { markLocal(); true }
    }.getOrDefault(false)
}

/** Read status is verified in both native namespaces before local rows are changed. */
fun Context.markThreadMessagesRead(threadId: Long): Boolean {
    if (threadId <= 0L) return false
    val id = threadId.toString()
    val smsSelection = "${Sms.THREAD_ID}=? AND ${Sms.TYPE}=? AND (${Sms.READ}=? OR ${Sms.SEEN}=?)"
    val smsArgs = arrayOf(id, Sms.MESSAGE_TYPE_INBOX.toString(), "0", "0")
    val mmsSelection = "${Mms.THREAD_ID}=? AND ${Mms.MESSAGE_BOX}=? AND (${Mms.READ}=? OR ${Mms.SEEN}=?)"
    val mmsArgs = arrayOf(id, Mms.MESSAGE_BOX_INBOX.toString(), "0", "0")
    val values = ContentValues().apply { put(Sms.READ, 1); put(Sms.SEEN, 1) }
    return markThreadReadAfterNative(
        roleHeld = SmsRole.isHeld(this),
        updateNative = {
            contentResolver.update(Sms.CONTENT_URI, values, smsSelection, smsArgs)
            contentResolver.update(Mms.CONTENT_URI, values, mmsSelection, mmsArgs)
        },
        smsHasUnread = { unreadProviderRow(Sms.CONTENT_URI, smsSelection, smsArgs) },
        mmsHasUnread = { unreadProviderRow(Mms.CONTENT_URI, mmsSelection, mmsArgs) },
        markLocal = { messagesDB.markThreadRead(threadId); conversationsDB.markRead(threadId) },
    )
}

private fun Context.unreadProviderRow(uri: Uri, selection: String, args: Array<String>): Boolean? =
    contentResolver.query(uri, arrayOf("_id"), selection, args, null)?.use { it.moveToFirst() }
