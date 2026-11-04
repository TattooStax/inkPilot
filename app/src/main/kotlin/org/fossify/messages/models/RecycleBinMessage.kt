package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import org.fossify.messages.messaging.MessageKey

/** Unknown legacy tombstones are retained for review; never used for provider deletion. */
@Entity(tableName = "recycle_bin_messages", primaryKeys = ["id", "kind_code"])
data class RecycleBinMessage(
    val id: Long,
    @ColumnInfo(name = "deleted_ts") var deletedTS: Long,
    @ColumnInfo(name = "kind_code") val kindCode: Int = LEGACY_UNKNOWN
) {
    constructor(key: MessageKey, deletedTS: Long) : this(key.id, deletedTS, key.origin.ordinal)

    companion object {
        const val LEGACY_UNKNOWN = 4
    }
}
