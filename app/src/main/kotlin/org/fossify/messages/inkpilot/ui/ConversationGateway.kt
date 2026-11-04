package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.content.ContentUris
import android.database.Cursor
import android.provider.Telephony
import org.fossify.messages.extensions.*
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message

internal data class ThreadCursor(val dateMillis: Long, val threadId: Long)
internal data class MessageCursor(val dateMillis: Long, val key: MessageKey)
internal data class Page<T, C>(val items: List<T>, val next: C?)

/** Provider-owned ordering uses full timestamps and table-qualified identities, never offsets. */
internal class ConversationGateway(private val context: Context) {
    fun pageThreads(after: ThreadCursor?, limit: Int): Page<Conversation, ThreadCursor> {
        require(limit in 1..200)
        val selection = "message_count > 0" + if (after == null) "" else " AND (date < ? OR (date = ? AND _id < ?))"
        val args = after?.let { arrayOf(it.dateMillis.toString(), it.dateMillis.toString(), it.threadId.toString()) }
        val rows = mutableListOf<Pair<Conversation, ThreadCursor>>()
        var last: ThreadCursor? = null
        var exhausted = true
        context.contentResolver.query(Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build(),
            arrayOf("_id", "date"), selection, args, "date DESC, _id DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                last = ThreadCursor(cursor.getLong(1), id)
                val conversation = context.getConversations(id).firstOrNull()?.takeUnless { it.isArchived } ?: continue
                rows += conversation to last!!
                if (rows.size == limit) { exhausted = !cursor.moveToNext(); break }
            }
        }
        return Page(rows.map { it.first }, if (exhausted) null else last)
    }

    fun pageMessages(threadId: Long, after: MessageCursor?, limit: Int): Page<Message, MessageCursor> {
        require(limit in 1..200)
        val rows = mutableListOf<Pair<Message, MessageCursor>>()
        listOf(false, true).forEach { mms ->
            val origin = if (mms) MessageOrigin.MMS else MessageOrigin.SMS
            val scale = if (mms) 1000L else 1L
            val uri = if (mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
            val boundary = messageBoundary(after, origin)
            val args = arrayOf(threadId.toString(), *boundary.args.toTypedArray())
            context.contentResolver.query(uri, null, "thread_id = ?${boundary.selection}", args, "date DESC, _id DESC")?.use { cursor ->
                var count = 0
                while (count < limit + 1 && cursor.moveToNext()) {
                    val id = cursor.long("_id")
                    val message = if (mms) context.getMMS(ContentUris.withAppendedId(uri, id)) else sms(cursor)
                    if (message != null) {
                        rows += message to MessageCursor(cursor.long("date") * scale, MessageKey(origin, id))
                        count++
                    }
                }
            }
        }
        val sorted = rows.sortedWith(compareByDescending<Pair<Message, MessageCursor>> { it.second.dateMillis }
            .thenByDescending { it.second.key.origin.ordinal }.thenByDescending { it.second.key.id })
        val page = sorted.take(limit)
        return Page(page.map { it.first }, if (sorted.size > limit) page.last().second else null)
    }

    fun markRead(threadId: Long) {
        val unread = listOf(Telephony.Sms.CONTENT_URI to "type", Telephony.Mms.CONTENT_URI to "msg_box").any { (uri, type) ->
            context.contentResolver.query(uri, arrayOf("_id"),
                "thread_id = ? AND $type = 1 AND (read = 0 OR seen = 0)", arrayOf(threadId.toString()), null)
                ?.use { it.moveToFirst() } == true
        }
        // Avoid a self-refresh loop on providers which notify even for zero-row updates.
        if (unread) context.markThreadMessagesRead(threadId)
    }

    private fun sms(cursor: Cursor): Message {
        val address = cursor.string("address")
        val thread = cursor.long("thread_id")
        return Message(cursor.long("_id"), cursor.string("body"), cursor.long("type").toInt(), cursor.long("status").toInt(),
            context.getThreadParticipants(thread, null), (cursor.long("date") / 1000).toInt(), cursor.long("read") == 1L,
            thread, false, null, address, address, "", cursor.long("sub_id").toInt())
    }
    private fun Cursor.long(name: String): Long = getColumnIndex(name).let { if (it < 0 || isNull(it)) 0 else getLong(it) }
    private fun Cursor.string(name: String): String = getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }
}
