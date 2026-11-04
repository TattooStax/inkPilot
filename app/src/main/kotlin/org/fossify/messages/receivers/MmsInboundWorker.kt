package org.fossify.messages.receivers

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import org.fossify.messages.R
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.inkpilot.runtime.AgentSettings
import java.util.concurrent.TimeUnit

/** The library callback is a thread after BroadcastReceiver completion; hand off durable work. */
class MmsInboundWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, 0).takeIf { it > 0 } ?: return Result.failure()
        if (!SmsRole.isHeld(applicationContext)) return Result.retry()
        return try {
            val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            val mms = applicationContext.getMMS(uri) ?: return Result.retry()
            if (mms.id != id || !mms.isMMS) return Result.failure()
            val address = mms.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: mms.senderPhoneNumber
            val size = applicationContext.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
            MmsReceiver().handleMmsMessage(applicationContext, mms, size, address)
            Result.success()
        } catch (_: Exception) {
            AgentSettings(applicationContext).recordStatus("MMS intake is waiting for a native provider read. Open SMS to review.")
            Result.retry()
        }
    }

    companion object {
        private const val KEY_ID = "native_mms_id"
        internal fun enqueue(context: Context, id: Long) {
            require(id > 0)
            val work = OneTimeWorkRequestBuilder<MmsInboundWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("inkpilot-mms:$id", ExistingWorkPolicy.KEEP, work)
                .result.get(10, TimeUnit.SECONDS)
        }
    }
}
