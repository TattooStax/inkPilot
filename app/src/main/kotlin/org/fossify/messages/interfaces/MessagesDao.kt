@file:Suppress("MaxLineLength")
package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.messaging.key
import org.fossify.messages.models.Message

@Dao
interface MessagesDao : LegacyRecycleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(message: Message)

    /** Replacing scheduled kind must commit or roll back both identities together. */
    @Transaction
    fun replaceScheduled(message: Message, previousKey: MessageKey?) {
        require(message.isScheduled && (previousKey == null || previousKey.isScheduled()))
        insertOrUpdate(message)
        if (previousKey != null && previousKey != message.key()) delete(previousKey)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(vararg message: Message)

    @Query("SELECT * FROM messages")
    fun getAll(): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END) WHERE recycle_bin_messages.id IS NOT NULL")
    fun getAllRecycleBinMessages(): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END) WHERE recycle_bin_messages.id IS NULL AND is_scheduled = 1")
    fun getAllScheduledMessages(): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END) WHERE recycle_bin_messages.id IS NOT NULL AND recycle_bin_messages.deleted_ts < :timestamp")
    fun getOldRecycleBinMessages(timestamp: Long): List<Message>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId")
    fun getThreadMessages(threadId: Long): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END) WHERE recycle_bin_messages.id IS NULL AND thread_id = :threadId")
    fun getNonRecycledThreadMessages(threadId: Long): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END) WHERE recycle_bin_messages.id IS NOT NULL AND thread_id = :threadId")
    fun getThreadMessagesFromRecycleBin(threadId: Long): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END) WHERE recycle_bin_messages.id IS NULL AND thread_id = :threadId AND is_scheduled = 1")
    fun getScheduledThreadMessages(threadId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId AND id = :messageId AND is_scheduled = 1")
    fun getScheduledMessagesWithId(threadId: Long, messageId: Long): List<Message>

    fun getScheduledMessageWithId(threadId: Long, messageId: Long): Message =
        getScheduledMessagesWithId(threadId, messageId).single()

    @Query("SELECT COUNT(*) FROM recycle_bin_messages")
    fun getArchivedCount(): Int

    @Query("SELECT * FROM messages WHERE body LIKE :text")
    fun getMessagesWithText(text: String): List<Message>

    @Query("UPDATE messages SET read = 1 WHERE id = :id AND is_mms = :isMms AND is_scheduled = 0")
    fun markRead(id: Long, isMms: Boolean)

    @Query("UPDATE messages SET read = 1 WHERE thread_id = :threadId")
    fun markThreadRead(threadId: Long)

    @Query("UPDATE messages SET type = :type WHERE id = :id AND is_mms = 0 AND is_scheduled = 0")
    fun updateType(id: Long, type: Int): Int

    @Query("UPDATE messages SET status = :status WHERE id = :id AND is_mms = 0 AND is_scheduled = 0")
    fun updateStatus(id: Long, status: Int): Int

    @Query("SELECT * FROM messages WHERE id = :id AND is_mms = :isMms AND is_scheduled = :isScheduled")
    fun getByIdentity(id: Long, isMms: Boolean, isScheduled: Boolean): Message?

    fun get(key: MessageKey): Message? = getByIdentity(key.id, key.isMms(), key.isScheduled())

    @Transaction
    fun delete(key: MessageKey) {
        deleteFromMessages(key.id, key.isMms(), key.isScheduled())
        deleteFromRecycleBin(key.id, key.origin.ordinal)
    }

    @Query("DELETE FROM messages WHERE id = :id AND is_mms = :isMms AND is_scheduled = :isScheduled")
    fun deleteFromMessages(id: Long, isMms: Boolean, isScheduled: Boolean)

    fun deleteFromRecycleBin(key: MessageKey) = deleteFromRecycleBin(key.id, key.origin.ordinal)

    @Transaction
    fun deleteThreadMessages(threadId: Long) {
        deleteThreadMessagesFromRecycleBin(threadId)
        deleteAllThreadMessages(threadId)
    }

    @Query("DELETE FROM messages WHERE thread_id = :threadId")
    fun deleteAllThreadMessages(threadId: Long)

    @Query("DELETE FROM recycle_bin_messages WHERE EXISTS (SELECT 1 FROM messages WHERE thread_id = :threadId AND messages.id = recycle_bin_messages.id AND recycle_bin_messages.kind_code = (CASE WHEN messages.is_scheduled = 1 THEN 2 ELSE 0 END + CASE WHEN messages.is_mms = 1 THEN 1 ELSE 0 END))")
    fun deleteThreadMessagesFromRecycleBin(threadId: Long)

    @Query("DELETE FROM messages")
    fun deleteAll()
}

private fun MessageKey.isMms() = origin == MessageOrigin.MMS || origin == MessageOrigin.SCHEDULED_MMS
private fun MessageKey.isScheduled() = origin == MessageOrigin.SCHEDULED_SMS || origin == MessageOrigin.SCHEDULED_MMS
