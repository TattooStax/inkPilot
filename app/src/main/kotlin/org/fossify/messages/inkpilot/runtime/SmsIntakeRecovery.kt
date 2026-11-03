package org.fossify.messages.inkpilot.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.canonicalStudioNumber

/** Repairs the gap between the native inbox commit and business-record intake. Never sends or infers. */
object SmsIntakeRecovery {
    private val checkpointLock = Any()

    fun recover(context: Context): Unit = synchronized(checkpointLock) {
        val app = context.applicationContext
        if (!canReadInbox(app)) return@synchronized
        val preferences = app.getSharedPreferences("inkpilot-intake-recovery", Context.MODE_PRIVATE)
        val afterId = preferences.getLong("provider-id", 0).coerceAtLeast(0)
        @Suppress("DEPRECATION")
        val installedAt = app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
        val checkpoint = StudioRepository(app).use { scan(app, it, afterId, installedAt, 200) }
        if (checkpoint != afterId) {
            check(preferences.edit().putLong("provider-id", checkpoint).commit()) { "Could not save SMS recovery checkpoint" }
        }
    }

    /** Returns the last committed or deliberately skipped row. Any storage failure aborts progress. */
    internal fun scan(
        context: Context, repository: StudioRepository, afterId: Long, sinceMillis: Long, limit: Int,
    ): Long {
        require(afterId >= 0 && sinceMillis >= 0 && limit in 1..500)
        if (!canReadInbox(context)) return afterId
        var checkpoint = afterId
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.SUBSCRIPTION_ID,
        )
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection,
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.DATE} >= ?",
            arrayOf(afterId.toString(), sinceMillis.toString()), "${Telephony.Sms._ID} ASC",
        ).use { cursor ->
            checkNotNull(cursor) { "Cannot read the native SMS inbox" }
            var handled = 0
            // Bound work even on providers that ignore structured query-limit hints.
            while (handled < limit && cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val threadId = cursor.getLong(1)
                val address = cursor.getString(2).orEmpty()
                val body = cursor.getString(3).orEmpty()
                val receivedAt = cursor.getLong(4)
                val subscription = if (cursor.isNull(5)) -1 else cursor.getInt(5)
                val number = try { canonicalStudioNumber(address) } catch (_: IllegalArgumentException) { null }
                if (threadId > 0 && number != null && body.isNotBlank() && body.length <= 16_000) {
                    repository.recordInbound("sms:$id", threadId, number, body, number, receivedAt, subscription)
                }
                checkpoint = id
                handled++
            }
        }
        return checkpoint
    }

    private fun canReadInbox(context: Context): Boolean =
        org.fossify.messages.helpers.SmsRole.isHeld(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
