package org.fossify.messages.transport

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * One outbound pickup run, run as lifecycle-managed work: the
 * platform owns this work's lifetime, so a run outlives
 * whatever enqueued it, and work the process dies before
 * running is run afterwards instead of being lost with the
 * process.
 *
 * A run that sends nothing is an outcome, not a failure of the
 * work: an empty queue is the idle phone's normal state, a
 * failed pickup leaves the brain's queue held there for the
 * next scheduled run, and a refused send waits on the role or
 * the pairing being restored — retrying from here cannot fix
 * any of those, and the scheduled pickup is the retry the
 * design already keeps. The work therefore reports success
 * once its one run has run, whatever the run picked up. The
 * run is handed this worker's stopped state: a worker the
 * platform has stopped (its constraints lost, its deadline
 * reached, its chain cancelled by a disconnect) stops picking
 * up and sending instead of running on past its own lifetime.
 */
class OutboundPickupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        // Every trigger of a pickup run runs under the pickup
        // guard, so a scheduled run and a pairing-triggered run
        // never pick the same queued work up at once (see
        // OutboundPickupGuard).
        synchronized(OutboundPickupGuard.lock) {
            applicationContext.pickUpAndSendOutboundWork { isStopped }
        }
        return Result.success()
    }
}
