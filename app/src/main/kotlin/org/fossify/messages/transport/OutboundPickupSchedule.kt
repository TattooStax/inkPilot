package org.fossify.messages.transport

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import inkstax.sms.transport.DRAIN_RETRY_INTERVAL_MILLIS
import inkstax.sms.transport.isLocalCompanionEndpoint
import java.util.concurrent.TimeUnit

private const val OUTBOUND_PICKUP_WORK_NAME = "outbound_pickup"
private const val OUTBOUND_PICKUP_PERIODIC_WORK_NAME = "outbound_pickup_periodic"

/**
 * The triggers for an outbound pickup run: while this phone is
 * paired, periodic lifecycle-managed work picks queued work up
 * at the interval the :transport definitions set for a blocked
 * drain's retry — the heartbeat interval — and a pairing, a
 * boot, or a Settings visit can each ask for a run at once as
 * well.
 *
 * Same-handset InkStax queues signal the SMS role app. A remote pairing
 * still uses periodic pickup, and a local pairing can poll its loopback
 * bridge even when the phone has no default Internet network.
 *
 * Updating periodic work preserves its enqueue time while replacing stale
 * network constraints after an app upgrade. One-time pickup uses the same
 * named chain on every wake. A cancelled pairing cannot cancel its successor.
 * The pickup guard serializes periodic and immediate runs.
 *
 * Pairing starts the schedule and disconnect cancels it,
 * together with the heartbeat schedule and the drain retry
 * both mirror: a phone with no credential has no endpoint to
 * pick up from. A pickup of an empty queue is a defined
 * outcome, so a paired phone with nothing queued pays one
 * empty pickup per interval.
 */
fun Context.ensureOutboundPickupSchedule() {
    val credential = storedDeviceCredential() ?: return
    val constraints = pickupConstraints(credential.endpointBase)
    val periodicRequest = PeriodicWorkRequestBuilder<OutboundPickupWorker>(
        DRAIN_RETRY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS
    ).setConstraints(constraints).build()
    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        OUTBOUND_PICKUP_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest
    )
}

/** Enqueues one outbound pickup run at once, as lifecycle-managed work. */
fun Context.enqueueOutboundPickup(): Operation? {
    val credential = storedDeviceCredential() ?: return null
    val request = OneTimeWorkRequestBuilder<OutboundPickupWorker>()
        .setConstraints(pickupConstraints(credential.endpointBase))
        .build()
    return WorkManager.getInstance(this).enqueueUniqueWork(
        OUTBOUND_PICKUP_WORK_NAME,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        request
    )
}

private fun pickupConstraints(endpoint: String): Constraints =
    Constraints.Builder().setRequiredNetworkType(
        if (isLocalCompanionEndpoint(endpoint)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
    ).build()

/** Cancels any scheduled outbound pickup, as disconnect requires. */
fun Context.cancelOutboundPickupSchedule() {
    val workManager = WorkManager.getInstance(this)
    workManager.cancelUniqueWork(OUTBOUND_PICKUP_PERIODIC_WORK_NAME)
    workManager.cancelUniqueWork(OUTBOUND_PICKUP_WORK_NAME)
}
