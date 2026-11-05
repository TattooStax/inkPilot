package org.fossify.messages.messaging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.Telephony.Sms
import java.util.UUID

/** A provider row is one SMS send attempt; all parts must agree before it can be shown as sent. */
internal data class SmsPartClaim(val rowId: Long, val attempt: String, val count: Int) {
    fun callbackUri(part: Int): Uri = ContentUris.withAppendedId(Sms.CONTENT_URI, rowId).buildUpon()
        .appendQueryParameter("attempt", attempt).appendQueryParameter("part", part.toString()).build()
}

internal data class SmsPartCallback(val rowId: Long, val attempt: String, val part: Int) {
    companion object {
        fun parse(uri: Uri?): SmsPartCallback? {
            val row = canonicalSmsCallbackRow(uri) ?: return null
            if (uri!!.queryParameterNames != setOf("attempt", "part") ||
                uri.getQueryParameters("attempt").size != 1 || uri.getQueryParameters("part").size != 1) return null
            val attempt = uri.getQueryParameter("attempt") ?: return null
            if (runCatching { UUID.fromString(attempt).toString() }.getOrNull() != attempt) return null
            val part = uri.getQueryParameter("part")?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return SmsPartCallback(ContentUris.parseId(row), attempt, part)
        }
    }
}

internal enum class SmsPartState { DISPATCHING, PARTIAL_FAILED, READY_SENT, READY_FAILED, SENT, FAILED, UNKNOWN }

private val NATIVE_TERMINAL_STATES = setOf(SmsPartState.READY_SENT, SmsPartState.READY_FAILED,
    SmsPartState.SENT, SmsPartState.FAILED)
private fun SmsPartState.disagreesWith(type: Int?): Boolean = when (this) {
    SmsPartState.SENT -> type != Sms.MESSAGE_TYPE_SENT
    SmsPartState.FAILED -> type != Sms.MESSAGE_TYPE_FAILED
    else -> false
}

/** A missing or disputed callback remains non-retryable and visible for manual review. */
internal class SmsPartJournal(context: Context, name: String = "sms-part-verdicts.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE claims (" +
            "row_id INTEGER PRIMARY KEY, attempt TEXT NOT NULL, parts INTEGER NOT NULL," +
            " state TEXT NOT NULL, claimed_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE results (" +
            "row_id INTEGER NOT NULL, part INTEGER NOT NULL, outcome INTEGER NOT NULL," +
            " PRIMARY KEY(row_id, part))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun claim(rowUri: Uri, count: Int): SmsPartClaim? {
        require(count > 0)
        val rowId = canonicalSmsCallbackRow(rowUri)?.let(ContentUris::parseId) ?: return null
        val ticket = SmsPartClaim(rowId, UUID.randomUUID().toString(), count)
        val values = ContentValues().apply {
            put("row_id", rowId); put("attempt", ticket.attempt); put("parts", count)
            put("state", SmsPartState.DISPATCHING.name); put("claimed_at", System.currentTimeMillis())
        }
        return ticket.takeIf {
            writableDatabase.insertWithOnConflict("claims", null, values, SQLiteDatabase.CONFLICT_IGNORE) > 0
        }
    }

    fun record(callback: SmsPartCallback, success: Boolean): SmsPartState? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val claim = read(db, callback.rowId) ?: return null
            if (claim.first.attempt != callback.attempt || callback.part !in 0 until claim.first.count) return null
            val previous = db.rawQuery("SELECT outcome FROM results WHERE row_id = ? AND part = ?",
                arrayOf(callback.rowId.toString(), callback.part.toString())).use {
                if (it.moveToFirst()) it.getInt(0) else null
            }
            val outcome = if (success) SUCCESS else FAILURE
            if (previous == null) db.insertOrThrow("results", null, ContentValues().apply {
                put("row_id", callback.rowId); put("part", callback.part); put("outcome", outcome)
            })
            val state = classify(db, callback.rowId, claim.first.count, claim.second, previous, outcome)
            setState(db, callback.rowId, state)
            db.setTransactionSuccessful()
            return state
        } finally { db.endTransaction() }
    }

    private fun classify(db: SQLiteDatabase, rowId: Long, count: Int, state: SmsPartState,
        previous: Int?, outcome: Int): SmsPartState {
        if (state == SmsPartState.UNKNOWN || previous != null && previous != outcome) return SmsPartState.UNKNOWN
        if (state == SmsPartState.SENT || state == SmsPartState.FAILED) return state
        val outcomes = db.rawQuery("SELECT outcome FROM results WHERE row_id = ?",
            arrayOf(rowId.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }
        if (outcomes.size < count && FAILURE in outcomes) return SmsPartState.PARTIAL_FAILED
        return when (partVerdict(outcomes, count)) {
            "CONFIRMED" -> SmsPartState.READY_SENT
            "FAILED" -> SmsPartState.READY_FAILED
            "UNKNOWN" -> SmsPartState.UNKNOWN
            else -> SmsPartState.DISPATCHING
        }
    }

    fun unknown(callback: SmsPartCallback) {
        writableDatabase.execSQL(
            "UPDATE claims SET state = ? WHERE row_id = ? AND attempt = ?",
            arrayOf<Any>(SmsPartState.UNKNOWN.name, callback.rowId, callback.attempt))
    }

    fun terminal(callback: SmsPartCallback, expected: SmsPartState, settled: SmsPartState): Boolean {
        require(expected == SmsPartState.READY_SENT || expected == SmsPartState.READY_FAILED)
        val db = writableDatabase
        val values = ContentValues().apply { put("state", settled.name) }
        val updated = db.update("claims", values, "row_id = ? AND attempt = ? AND state = ?",
            arrayOf(callback.rowId.toString(), callback.attempt, expected.name))
        if (updated == 1) return true
        // A UI read may reconcile a verified native terminal row before this callback returns.
        return read(db, callback.rowId)?.let { (claim, state) ->
            claim.attempt == callback.attempt && state == settled
        } == true
    }

    /** Readback after process death completes only a previously verified native terminal row. */
    fun state(rowId: Long, now: Long = System.currentTimeMillis(),
        exactNativeType: (() -> Int?)? = null): SmsPartState? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val (claim, previous) = read(db, rowId) ?: return null
            // Never reconcile from a cached Room type; the caller must read the exact native row.
            val nativeType = if (exactNativeType != null && previous in NATIVE_TERMINAL_STATES)
                runCatching { exactNativeType() }.getOrNull() else null
            val resolved = when {
                exactNativeType != null && previous.disagreesWith(nativeType) -> SmsPartState.UNKNOWN
                previous == SmsPartState.READY_SENT && nativeType == Sms.MESSAGE_TYPE_SENT -> SmsPartState.SENT
                previous == SmsPartState.READY_FAILED && nativeType == Sms.MESSAGE_TYPE_FAILED -> SmsPartState.FAILED
                previous in setOf(SmsPartState.DISPATCHING, SmsPartState.PARTIAL_FAILED,
                    SmsPartState.READY_SENT, SmsPartState.READY_FAILED) &&
                    now - claim.claimedAt >= STALE_AFTER_MILLIS -> SmsPartState.UNKNOWN
                else -> previous
            }
            if (resolved != previous) setState(db, rowId, resolved)
            db.setTransactionSuccessful()
            return resolved
        } finally { db.endTransaction() }
    }

    private data class Stored(val attempt: String, val count: Int, val claimedAt: Long)
    private fun read(db: SQLiteDatabase, rowId: Long): Pair<Stored, SmsPartState>? =
        db.rawQuery("SELECT attempt, parts, state, claimed_at FROM claims WHERE row_id = ?",
            arrayOf(rowId.toString())).use {
            if (!it.moveToFirst()) null else Stored(it.getString(0), it.getInt(1),
                it.getLong(it.getColumnIndexOrThrow("claimed_at"))) to SmsPartState.valueOf(it.getString(2))
        }
    private fun setState(db: SQLiteDatabase, rowId: Long, state: SmsPartState) {
        db.execSQL("UPDATE claims SET state = ? WHERE row_id = ?", arrayOf<Any>(state.name, rowId))
    }
    private companion object {
        const val SUCCESS = 1
        const val FAILURE = 2
        const val STALE_AFTER_MILLIS = 600_000L
    }
}

/** The carrier delivery report is registered for the last part only. */
internal fun SmsPartJournal.partCount(rowId: Long): Int? =
    readableDatabase.rawQuery("SELECT parts FROM claims WHERE row_id = ?", arrayOf(rowId.toString())).use {
        if (it.moveToFirst()) it.getInt(0) else null
    }

/** A synchronous handoff exception disputes every claim on this exact native row. */
internal fun SmsPartJournal.unknownRow(rowUri: Uri) {
    val rowId = canonicalSmsCallbackRow(rowUri)?.let(ContentUris::parseId) ?: return
    writableDatabase.execSQL("UPDATE claims SET state = ? WHERE row_id = ?",
        arrayOf<Any>(SmsPartState.UNKNOWN.name, rowId))
}
