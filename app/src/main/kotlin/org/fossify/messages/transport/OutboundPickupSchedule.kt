package org.fossify.messages.transport

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import inkstax.sms.transport.DRAIN_RETRY_INTERVAL_MILLIS
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
 * Outbound work is created at the brain, not on the phone, so
 * no phone-side event can trigger a run the way an arrival
 * triggers a drain: the schedule is the trigger, in the same
 * shape as the scheduled drain retry. The interval is the
 * heartbeat interval for the drain retry's own reason, one
 * level up: the brain already judges this link by that pace,
 * picking up faster would spend battery re-asking an endpoint
 * that has nothing new to say at any pace the link's health
 * can distinguish, and the platform's own periodic-work floor
 * sits at the same interval. A wake path that can name the
 * moment work lands — the push provider the design keeps
 * behind its interface — shortens the wait when it arrives;
 * until then the periodic run is the honest pace, and a reply
 * the brain queues is picked up within one interval of being
 * queued.
 *
 * Periodic work is kept, not replaced, when the schedule is
 * ensured again: pairing, a boot, and every Settings visit all
 * ensure it, and keeping the existing work preserves its next
 * run time instead of pushing a pickup a full interval later
 * on every Settings visit. The one-time run an ensure can ask
 * for is unique and appended, so it runs after a run already
 * pending rather than beside it, and the pickup guard
 * serializes the runs themselves whichever trigger enqueued
 * them. The network constraint is the pickup's own
 * precondition, so a run that falls due while the phone is
 * offline waits for the network instead of burning its attempt
 * against an endpoint it cannot reach.
 *
 * Pairing starts the schedule and disconnect cancels it,
 * together with the heartbeat schedule and the drain retry
 * both mirror: a phone with no credential has no endpoint to
 * pick up from. A pickup of an empty queue is a defined
 * outcome, so a paired phone with nothing queued pays one
 * empty pickup per interval.
 */
fun Context.ensureOutboundPickupSchedule() {
    if (storedDeviceCredential() == null) {
        return
    }
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val periodicRequest = PeriodicWorkRequestBuilder<OutboundPickupWorker>(
        DRAIN_RETRY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS
    ).setConstraints(constraints).build()
    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        OUTBOUND_PICKUP_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest
    )
}

/** Enqueues one outbound pickup run at once, as lifecycle-managed work. */
fun Context.enqueueOutboundPickup() {
    val request = OneTimeWorkRequestBuilder<OutboundPickupWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        OUTBOUND_PICKUP_WORK_NAME,
        ExistingWorkPolicy.APPEND,
        request
    )
}

/** Cancels any scheduled outbound pickup, as disconnect requires. */
fun Context.cancelOutboundPickupSchedule() {
    val workManager = WorkManager.getInstance(this)
    workManager.cancelUniqueWork(OUTBOUND_PICKUP_PERIODIC_WORK_NAME)
    workManager.cancelUniqueWork(OUTBOUND_PICKUP_WORK_NAME)
}
