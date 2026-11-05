package org.fossify.messages.extensions

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue

/** Compare the native SMS millisecond date with the native MMS second date. */
internal fun latestThreadSnippet(
    mmsBody: String?,
    mmsDateSeconds: Int?,
    smsBody: String?,
    smsDateMillis: Long?,
): String {
    if (smsDateMillis != null &&
        (mmsDateSeconds == null || smsDateMillis > mmsDateSeconds.toLong() * 1000L)
    ) {
        return smsBody.orEmpty()
    }
    return mmsBody.orEmpty()
}

fun Context.getThreadSnippet(threadId: Long): String {
    val latestMms = getMMS(threadId, "${Mms.DATE} DESC LIMIT 1").firstOrNull()
    var smsBody: String? = null
    var smsDateMillis: Long? = null

    try {
        contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.BODY, Sms.DATE),
            "${Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Sms.DATE} DESC LIMIT 1",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                smsBody = cursor.getStringValue(Sms.BODY)
                smsDateMillis = cursor.getLongValue(Sms.DATE)
            }
        }
    } catch (_: Exception) {
        // Keep the MMS preview when SMS cannot be read.
    }

    return latestThreadSnippet(latestMms?.body, latestMms?.date, smsBody, smsDateMillis)
}
