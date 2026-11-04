package org.fossify.messages.extensions

import android.content.Context
import android.provider.Telephony
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.models.Message

/** Refresh imports each provider namespace independently, preserving local scheduled rows. */
fun Context.reconcileTypedMessageCache(providerMessages: List<Message>) {
    messagesDB.insertMessages(*providerMessages.filterNot { it.isScheduled }.toTypedArray())
    for (legacy in messagesDB.getUnresolvedRecycleBinEntries()) {
        // A denied, failed or null query is not evidence that the other namespace is absent.
        val sms = providerContains(Telephony.Sms.CONTENT_URI, legacy.id) ?: continue
        val mms = providerContains(Telephony.Mms.CONTENT_URI, legacy.id) ?: continue
        val origin = resolveLegacyRecycleOrigin(sms, mms) ?: continue
        messagesDB.resolveLegacyRecycleEntry(legacy.id, legacy.deletedTS, origin.ordinal)
    }
}

private fun Context.providerContains(uri: android.net.Uri, id: Long): Boolean? = try {
    contentResolver.query(uri, arrayOf("_id"), "_id = ?", arrayOf(id.toString()), null)
        ?.use { it.moveToFirst() }
} catch (_: Exception) {
    null
}

/** Null means absence was not established, so no source identity can safely be assigned. */
internal fun resolveLegacyRecycleOrigin(smsExists: Boolean?, mmsExists: Boolean?): MessageOrigin? = when {
    smsExists == true && mmsExists == false -> MessageOrigin.SMS
    mmsExists == true && smsExists == false -> MessageOrigin.MMS
    else -> null
}
