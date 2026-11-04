package org.fossify.messages.extensions

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import org.fossify.messages.interfaces.MessagesDao
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.models.Message

/** Read-only seam: reconciliation cannot mutate either Telephony namespace. */
internal fun interface MessageProviderReadPort {
    fun query(origin: MessageOrigin, id: Long): Cursor?
}

/** Refresh imports each provider namespace independently, preserving local scheduled rows. */
fun Context.reconcileTypedMessageCache(providerMessages: List<Message>) = reconcileTypedMessageCache(
    messagesDB, providerMessages, MessageProviderReadPort { origin, id ->
        val uri = when (origin) {
            MessageOrigin.SMS -> Telephony.Sms.CONTENT_URI
            MessageOrigin.MMS -> Telephony.Mms.CONTENT_URI
            else -> error("Only provider origins can be queried")
        }
        contentResolver.query(uri, arrayOf("_id"), "_id = ?", arrayOf(id.toString()), null)
    }
)

internal fun reconcileTypedMessageCache(
    dao: MessagesDao,
    providerMessages: List<Message>,
    provider: MessageProviderReadPort
) {
    dao.insertMessages(*providerMessages.filterNot { it.isScheduled }.toTypedArray())
    for (legacy in dao.getUnresolvedRecycleBinEntries()) {
        // A denied, failed or null query is not evidence that the other namespace is absent.
        val sms = provider.contains(MessageOrigin.SMS, legacy.id) ?: continue
        val mms = provider.contains(MessageOrigin.MMS, legacy.id) ?: continue
        val origin = resolveLegacyRecycleOrigin(sms, mms) ?: continue
        dao.resolveLegacyRecycleEntry(legacy.id, legacy.deletedTS, origin.ordinal)
    }
}

private fun MessageProviderReadPort.contains(origin: MessageOrigin, id: Long): Boolean? = try {
    query(origin, id)?.use { it.moveToFirst() }
} catch (_: Exception) {
    null
}

/** Null means absence was not established, so no source identity can safely be assigned. */
internal fun resolveLegacyRecycleOrigin(smsExists: Boolean?, mmsExists: Boolean?): MessageOrigin? = when {
    smsExists == true && mmsExists == false -> MessageOrigin.SMS
    mmsExists == true && smsExists == false -> MessageOrigin.MMS
    else -> null
}
