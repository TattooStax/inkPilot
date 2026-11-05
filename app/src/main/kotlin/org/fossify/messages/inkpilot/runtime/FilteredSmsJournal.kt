package org.fossify.messages.inkpilot.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Private, recoverable record of SMS that could not be safely delivered or was filtered. */
internal class FilteredSmsJournal(context: Context, name: String = "inkpilot-filtered-sms.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {

    data class Entry(val receipt: String, val sender: String, val body: String, val receivedAt: Long, val reason: String)
    data class Cursor(val receivedAt: Long, val receipt: String)
    data class Page(val entries: List<Entry>, val next: Cursor?)

    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE skipped_sms (receipt TEXT PRIMARY KEY NOT NULL, sender TEXT NOT NULL, " +
            "body TEXT NOT NULL, received_at INTEGER NOT NULL, subscription_id INTEGER NOT NULL, reason TEXT NOT NULL)"
    )

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("No filtered SMS migration from $oldVersion to $newVersion")

    fun record(sender: String, body: String, receivedAt: Long, subscriptionId: Int, reason: String) {
        require(sender.isNotBlank() && sender.length <= 256 && receivedAt >= 0)
        require(reason in setOf("keyword", "blocked", "unknown_sender", "provider_failed"))
        val receipt = receiptOf(sender, body, receivedAt, subscriptionId, reason)
        val row = ContentValues().apply {
            put("receipt", receipt); put("sender", sender); put("body", body)
            put("received_at", receivedAt); put("subscription_id", subscriptionId); put("reason", reason)
        }
        writableDatabase.insertWithOnConflict("skipped_sms", null, row, SQLiteDatabase.CONFLICT_IGNORE)
        readableDatabase.rawQuery("SELECT 1 FROM skipped_sms WHERE receipt = ?", arrayOf(receipt)).use {
            check(it.moveToFirst()) { "Could not preserve filtered SMS" }
        }
    }

    fun remove(receipt: String): Boolean = writableDatabase.delete(
        "skipped_sms", "receipt = ?", arrayOf(receipt)
    ) == 1

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM skipped_sms", null).use {
        check(it.moveToFirst()); it.getLong(0)
    }

    fun latest(limit: Int = 50): List<Entry> = page(limit = limit).entries

    fun page(before: Cursor? = null, limit: Int = 50): Page {
        require(limit in 1..100)
        val condition = if (before == null) "" else
            "WHERE received_at < ? OR (received_at = ? AND receipt < ?)"
        val args = if (before == null) arrayOf((limit + 1).toString()) else
            arrayOf(before.receivedAt.toString(), before.receivedAt.toString(), before.receipt, (limit + 1).toString())
        val rows = readableDatabase.rawQuery(
            "SELECT receipt, sender, body, received_at, reason FROM skipped_sms $condition " +
                "ORDER BY received_at DESC, receipt DESC LIMIT ?", args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Entry(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getString(4)))
            }
        }
        val visible = rows.take(limit)
        val next = if (rows.size > limit) visible.last().let { Cursor(it.receivedAt, it.receipt) } else null
        return Page(visible, next)
    }

    private fun receiptOf(sender: String, body: String, receivedAt: Long, subscriptionId: Int, reason: String): String {
        val input = listOf(sender, body, receivedAt, subscriptionId, reason).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
