package org.fossify.messages.inkpilot.runtime

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.inkpilot.data.SendClaim
import org.fossify.messages.inkpilot.data.SendStatus
import org.fossify.messages.inkpilot.data.StudioRepository

internal object SmsProviderHistory {
    fun insert(context: Context, claim: SendClaim): Long {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, claim.recipient)
            put(Telephony.Sms.BODY, claim.body)
            put(Telephony.Sms.THREAD_ID, claim.threadId)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.SUBSCRIPTION_ID, claim.subscriptionId)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            ?: error("Could not save the outgoing message.")
        return ContentUris.parseId(uri).also { check(it > 0) }
    }

    fun finish(context: Context, claim: SendClaim, state: SendStatus) {
        val id = claim.providerId ?: return
        // Unknown attempts remain visibly pending. Never turn ambiguity into a delivery claim.
        if (state != SendStatus.SENT && state != SendStatus.FAILED) return
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, if (state == SendStatus.SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
            put(Telephony.Sms.STATUS, if (state == SendStatus.SENT) Telephony.Sms.STATUS_NONE else Telephony.Sms.STATUS_FAILED)
        }
        val synced = runCatching {
            val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
            if (context.contentResolver.update(uri, values, null, null) > 0) true
            else context.contentResolver.query(uri, arrayOf(Telephony.Sms._ID), null, null, null)?.use { !it.moveToFirst() } == true
        }.getOrDefault(false)
        if (synced) StudioRepository(context).use { it.markProviderSynced(claim.draftId, id) }
        refresh(context, claim.threadId)
    }

    fun reconcile(context: Context) {
        if (!org.fossify.messages.helpers.SmsRole.isHeld(context)) return
        StudioRepository(context).use { repository ->
            repository.pendingProviderUpdates().forEach { finish(context, it, it.status) }
        }
    }

    fun refresh(context: Context, threadId: Long) {
        runCatching {
            context.getMessages(threadId).forEach { context.messagesDB.insertOrUpdate(it) }
            context.getConversations(threadId).firstOrNull()?.let { context.insertOrUpdateConversation(it) }
            refreshMessages()
            refreshConversations()
        }
    }
}
