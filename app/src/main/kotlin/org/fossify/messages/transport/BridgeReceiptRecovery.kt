package org.fossify.messages.transport

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Native history reconciliation must survive disconnect and offline periods.
 * Reuse the report drain implementation and execution guard, with no network
 * prerequisite. Its retained-verdict check requests backoff until filing settles.
 * Appending preserves a callback arriving while an earlier recovery is finishing.
 */
internal fun Context.enqueueBridgeReceiptRecovery() {
    WorkManager.getInstance(this).enqueueUniqueWork(
        "bridge_receipt_recovery",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<PendingReportDrainWorker>().build()
    )
}
