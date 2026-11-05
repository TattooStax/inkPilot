package org.fossify.messages.inkpilot.runtime

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import java.security.MessageDigest

/** Only an exact, durable native-row receipt permits recovery to create an agent source. */
internal class SmsAdmissionLedger(private val db: SQLiteDatabase) {
    private companion object {
        const val ID = 0
        const val THREAD = 1
        const val ADDRESS = 2
        const val BODY = 3
        const val DATE = 4
        const val SIM = 5
        const val TYPE = 6
    }

    data class NativeIdentity(
        val thread: Long, val sender: String, val body: String, val date: Long, val subscription: Int,
    )

    fun admitNative(id: Long, thread: Long, sender: String, body: String, date: Long, subscription: Int) {
        require(id > 0 && thread > 0)
        val fingerprint = fingerprint(thread, sender, body, date, subscription)
        db.insertWithOnConflict("admitted_sms", null, ContentValues().apply {
            put("provider_id", id); put("fingerprint", fingerprint)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(admitsNative(id, thread, sender, body, date, subscription)) { "SMS admission was not durable" }
    }

    /** Fail closed if a provider row differs from the classified broadcast. */
    fun admitFromProvider(context: Context, id: Long, expected: NativeIdentity): Boolean {
        val actual = readProviderIdentity(context, id) ?: return false
        if (actual.thread <= 0 || actual != expected) return false
        admitNative(id, actual.thread, actual.sender, actual.body, actual.date, actual.subscription)
        return true
    }

    private fun readProviderIdentity(context: Context, id: Long): NativeIdentity? {
        if (id <= 0) return null
        val columns = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.TYPE)
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        val cursor = context.contentResolver.query(uri, columns, null, null, null) ?: return null
        return cursor.use {
            when {
                !it.moveToFirst() -> null
                it.getLong(ID) != id || it.getInt(TYPE) != Telephony.Sms.MESSAGE_TYPE_INBOX -> null
                else -> NativeIdentity(it.getLong(THREAD), it.getString(ADDRESS).orEmpty(),
                    it.getString(BODY).orEmpty(), it.getLong(DATE),
                    if (it.isNull(SIM)) -1 else it.getInt(SIM))
            }
        }
    }

    fun admitsNative(id: Long, thread: Long, sender: String, body: String, date: Long, subscription: Int): Boolean =
        db.rawQuery("SELECT fingerprint FROM admitted_sms WHERE provider_id = ?", arrayOf(id.toString())).use {
            it.moveToFirst() && it.getString(0) == fingerprint(thread, sender, body, date, subscription)
        }

    fun hasAdmission(id: Long): Boolean =
        db.rawQuery("SELECT 1 FROM admitted_sms WHERE provider_id = ?", arrayOf(id.toString())).use { it.moveToFirst() }

    fun removeAdmission(id: Long) {
        require(id > 0)
        db.delete("admitted_sms", "provider_id = ?", arrayOf(id.toString()))
    }

    fun pruneAdmissions(throughId: Long) {
        require(throughId >= 0)
        db.delete("admitted_sms", "provider_id <= ?", arrayOf(throughId.toString()))
    }

    private fun fingerprint(thread: Long, sender: String, body: String, date: Long, subscription: Int): String =
        MessageDigest.getInstance("SHA-256").digest(
            listOf(thread, sender, body, date, subscription).joinToString("\u0000").toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
}
