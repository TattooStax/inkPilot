package org.fossify.messages.interfaces

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.fossify.messages.models.RecycleBinMessage

interface LegacyRecycleDao {
    @Query("SELECT * FROM recycle_bin_messages WHERE kind_code = 4")
    fun getUnresolvedRecycleBinEntries(): List<RecycleBinMessage>

    @Transaction
    fun resolveLegacyRecycleEntry(id: Long, deletedTs: Long, kindCode: Int) {
        require(kindCode == 0 || kindCode == 1)
        if (getUnresolvedRecycleBinEntries().none { it.id == id && it.deletedTS == deletedTs }) return
        insertRecycleBinEntryIfAbsent(RecycleBinMessage(id, deletedTs, kindCode))
        retainLaterRecycleTimestamp(id, kindCode, deletedTs)
        deleteFromRecycleBin(id, RecycleBinMessage.LEGACY_UNKNOWN)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRecycleBinEntryIfAbsent(recycleBinMessage: RecycleBinMessage)

    @Query("UPDATE recycle_bin_messages SET deleted_ts = MAX(deleted_ts, :deletedTs) WHERE id = :id AND kind_code = :kindCode")
    fun retainLaterRecycleTimestamp(id: Long, kindCode: Int, deletedTs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRecycleBinEntry(recycleBinMessage: RecycleBinMessage)

    @Query("DELETE FROM recycle_bin_messages WHERE id = :id AND kind_code = :kindCode")
    fun deleteFromRecycleBin(id: Long, kindCode: Int)
}
