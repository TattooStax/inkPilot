package org.fossify.messages.messaging

import org.fossify.messages.extensions.messagesDB
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Separate durable dispatch journal: a missing callback never makes a send retryable. */
class ScheduledVerdicts(context: Context) : SQLiteOpenHelper(context, "scheduled-verdicts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE claims (identity TEXT PRIMARY KEY, state TEXT NOT NULL, parts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE parts (identity TEXT NOT NULL, part INTEGER NOT NULL, outcome INTEGER NOT NULL, PRIMARY KEY(identity, part))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun claim(key: MessageKey, parts: Int): Boolean {
        require(parts > 0)
        return writableDatabase.insertWithOnConflict("claims", null, ContentValues().apply {
            put("identity", key.alarmIdentity()); put("state", "DISPATCHING"); put("parts", parts)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Serialize edits and snapshot+claim across alarm/UI callers. No handoff in this transaction. */
    fun <T> claimPrepared(key: MessageKey, prepare: () -> Pair<T, Int>?): T? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (state(key) != "QUEUED") return null
            val (value, count) = prepare() ?: return null
            if (!claim(key, count)) return null
            db.setTransactionSuccessful()
            return value
        } finally { db.endTransaction() }
    }

    fun editQueued(keys: List<MessageKey>, persist: () -> Unit): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (keys.any { state(it) != "QUEUED" }) return false
            persist()
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun state(key: MessageKey): String = readableDatabase.rawQuery(
        "SELECT state FROM claims WHERE identity = ?", arrayOf(key.alarmIdentity())
    ).use { if (it.moveToFirst()) it.getString(0) else "QUEUED" }

    fun unknown(key: MessageKey) {
        writableDatabase.execSQL("UPDATE claims SET state = 'UNKNOWN' WHERE identity = ? AND state = 'DISPATCHING'", arrayOf(key.alarmIdentity()))
    }

    fun record(key: MessageKey, part: Int, success: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val (count, priorState) = db.rawQuery(
                "SELECT parts, state FROM claims WHERE identity = ?", arrayOf(key.alarmIdentity())
            ).use {
                if (!it.moveToFirst()) return
                it.getInt(0) to it.getString(1)
            }
            if (part !in 0 until count) return
            db.insertWithOnConflict("parts", null, ContentValues().apply {
                put("identity", key.alarmIdentity()); put("part", part); put("outcome", if (success) 1 else 2)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val outcomes = db.rawQuery("SELECT outcome FROM parts WHERE identity = ?", arrayOf(key.alarmIdentity())).use {
                buildList { while (it.moveToNext()) add(it.getInt(0)) }
            }
            val state = if (priorState == "UNKNOWN") "UNKNOWN" else partVerdict(outcomes, count)
            db.execSQL("UPDATE claims SET state = ? WHERE identity = ?", arrayOf(state, key.alarmIdentity()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

internal fun MessageKey.alarmIdentity() = "inkpilot-scheduled://dispatch/${origin.name}/$id"

/** Only explicit, typed identities are allowed to authorize dispatch. */
internal fun Intent.scheduledKey(): MessageKey? = data?.let { uri ->
    if (uri.scheme != "inkpilot-scheduled" || uri.host != "dispatch") return@let null
    val segments = uri.pathSegments
    if (segments.size != 2) return@let null
    val origin = MessageOrigin.entries.firstOrNull { it.name == segments[0] } ?: return@let null
    if (origin != MessageOrigin.SCHEDULED_SMS && origin != MessageOrigin.SCHEDULED_MMS) return@let null
    segments[1].toLongOrNull()?.let { MessageKey(origin, it) }
}

data class ScheduledCallback(val key: MessageKey, val offset: Int = 0) {
    fun attach(intent: Intent, part: Int) {
        intent.putExtra("scheduled_identity", key.alarmIdentity())
        intent.putExtra("scheduled_part", offset + part)
    }
}

/** Unverified native MMS callbacks are unknown, even when the carrier callback says OK. */
internal fun applyScheduledCallbackVerdict(
    nativeRowVerified: Boolean, success: Boolean, record: (Boolean) -> Unit, unknown: () -> Unit,
) {
    if (nativeRowVerified) record(success) else unknown()
}

fun Context.recordScheduledCallback(intent: Intent, success: Boolean, nativeRowVerified: Boolean = true) {
    val identity = intent.getStringExtra("scheduled_identity") ?: return
    val key = Intent().setData(android.net.Uri.parse(identity)).scheduledKey() ?: return
    val part = intent.getIntExtra("scheduled_part", -1)
    val confirmed = ScheduledVerdicts(this).use {
        applyScheduledCallbackVerdict(nativeRowVerified, success,
            record = { accepted -> it.record(key, part, accepted) },
            unknown = { it.unknown(key) })
        it.state(key) == "CONFIRMED"
    }
    // Journal commit precedes cleanup, so a crash cannot make an unknown send disappear.
    if (nativeRowVerified && confirmed) messagesDB.delete(key)
}
