package org.fossify.messages.inkpilot.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.canonicalStudioNumber

/** Recovers only positively admitted native SMS after an intake crash. Never sends or infers. */
object SmsIntakeRecovery {
    private val checkpointLock = Any()
    private val admissionLock = Any()
    private const val MAX_RECOVERABLE_BODY = 16_000

    /** The receiver inserts a native row and commits its admission before recovery can inspect it. */
    internal fun <T> withAdmissionLock(block: () -> T): T = synchronized(admissionLock) { block() }

    fun recover(context: Context): Unit = synchronized(checkpointLock) {
        val app = context.applicationContext
        if (!canReadInbox(app)) return@synchronized
        @Suppress("DEPRECATION")
        val installedAt = app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
        StudioRepository(app).use { repository ->
            recoverAndSchedule(app, repository, installedAt)
        }
    }

    internal fun recoverAndSchedule(
        context: Context, repository: StudioRepository, sinceMillis: Long, maxPages: Int = 10,
    ) {
        if (recoverPages(context, repository, sinceMillis, maxPages)) enqueueContinuation(context)
    }

    /** True means the bounded wake exhausted its page budget and needs durable continuation. */
    internal fun recoverPages(
        context: Context, repository: StudioRepository, sinceMillis: Long, maxPages: Int = 10,
    ): Boolean {
        require(maxPages in 1..10)
        FilteredSmsJournal(context).use { it.admissions.pruneAdmissions(repository.intakeCheckpoint()) }
        repeat(maxPages) {
            val afterId = repository.intakeCheckpoint()
            val checkpoint = scan(context, repository, afterId, sinceMillis, 200)
            if (checkpoint == afterId) return false
            repository.advanceIntakeCheckpoint(checkpoint)
            FilteredSmsJournal(context).use { it.admissions.pruneAdmissions(checkpoint) }
        }
        return true
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
        FilteredSmsJournal(context).use { journal ->
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
                    recoverRow(cursor, repository, journal)
                    checkpoint = id
                    handled++
                }
            }
        }
        return checkpoint
    }

    private fun recoverRow(
        cursor: android.database.Cursor, repository: StudioRepository, journal: FilteredSmsJournal,
    ) {
        val id = cursor.getLong(0)
        val thread = cursor.getLong(1)
        val address = cursor.getString(2).orEmpty()
        val body = cursor.getString(3).orEmpty()
        val receivedAt = cursor.getLong(4)
        val subscription = if (cursor.isNull(5)) -1 else cursor.getInt(5)
        val number = try { canonicalStudioNumber(address) } catch (_: IllegalArgumentException) { null }
        if (thread <= 0 || number == null) return
        if (body.isBlank() || body.length > MAX_RECOVERABLE_BODY) return
        val admitted = withAdmissionLock {
            journal.admissions.admitsNative(id, thread, address, body, receivedAt, subscription)
        }
        if (admitted) repository.recordInbound("sms:$id", thread, number, body, number, receivedAt, subscription)
        else {
            // Provider normalization or missing receipts stay native-only for staff review.
            journal.recordNativeReview(address, body, receivedAt, subscription)
        }
    }

    internal fun enqueueContinuation(context: Context) {
        androidx.work.WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<SmsIntakeRecoveryWorker>().addTag(CONTINUATION_TAG).build(),
        )
    }

    internal const val CONTINUATION_TAG = "inkpilot-intake-continuation"

    private fun canReadInbox(context: Context): Boolean =
        org.fossify.messages.helpers.SmsRole.isHeld(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
