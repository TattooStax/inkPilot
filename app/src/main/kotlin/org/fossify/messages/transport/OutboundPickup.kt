package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.OutboundPickupOutcome

/**
 * One outbound pickup run: pick queued work up from the paired
 * endpoint through the pickup binding and send each item
 * through the platform SMS API under the default-SMS role, one
 * item at a time, until the brain's queue reads empty — step
 * (5) of the outbound send path, the caller that joins the
 * pickup binding (step 2) to the sender (step 3).
 *
 * The whole run acts under the pairing that was stored when it
 * started. The stored credential is captured once, and both
 * the pickups and the sends use that credential's overloads,
 * so a disconnect and re-pair landing mid-run can never hand
 * one pairing's work to the other's endpoint or send it under
 * the other's identity: the next run reads the new pairing for
 * itself. A run with no usable credential picks nothing up —
 * outbound work exists only under a pairing, and an unpaired
 * or local-mode phone behaves exactly as it does without this
 * caller.
 *
 * A pickup hands one item over. The brain hands an item over
 * once — picking up is taking it, not looking at it — and the
 * phone's verdict on the send is reported back through the
 * outbound ack wire under that item's source id, never through
 * the pickup. The run therefore picks up again after each
 * send, and stops at the first answer that hands over no work:
 * an empty queue is the run's natural end, and a failed pickup
 * (an unreachable or refusing endpoint, a rejected credential,
 * a malformed answer) leaves whatever the brain still holds
 * queued there for a later run rather than being retried in a
 * tight loop here.
 *
 * A send outcome decides whether continuing can mean anything.
 * A submitted send, and a send that failed at submission — its
 * failure verdict already filed by the sender itself — both
 * leave the run able to pick the next item up. A send refused
 * because this app no longer holds the default-SMS role, or
 * because the pairing is gone, ends the run: every further
 * item would be refused the same way, and the work stays
 * queued at the brain, still un-acked, until the role or the
 * pairing is restored and a later run picks it up.
 *
 * One run never sends the same source id twice. If an answer
 * hands over a source id this run already sent, the run stops
 * instead of sending it again: the item's verdict is still
 * travelling through the ack path, and a second send of the
 * same reply would put a duplicate text in front of the
 * recipient that no verdict could honestly describe.
 *
 * This call blocks on the network and the radio, so it belongs
 * on a background thread — the pickup worker is its caller.
 * Returns how many items the run submitted or failed at
 * submission; the count is a record for the caller, and no
 * outcome of a single item is hidden by it — every send's
 * verdict travels the ack path whether the run reports it or
 * not.
 */
internal fun Context.pickUpAndSendOutboundWork(): Int {
    val credential = storedDeviceCredential() ?: return 0
    var sentCount = 0
    val seenSourceIds = HashSet<String>()
    var running = true
    while (running) {
        val outcome = when (val result = performOutboundPickup(credential, System.currentTimeMillis())) {
            OutboundPickupBindingResult.NoUsableCredential -> null
            is OutboundPickupBindingResult.Completed -> result.outcome
        }
        val work = (outcome as? OutboundPickupOutcome.WorkAvailable)?.work
        if (work == null || !seenSourceIds.add(work.sourceId)) {
            running = false
        } else {
            when (sendOutboundWork(credential, work)) {
                OutboundWorkSendResult.Submitted -> sentCount++
                is OutboundWorkSendResult.Failed -> sentCount++
                OutboundWorkSendResult.NotPaired,
                OutboundWorkSendResult.NotDefaultSmsApp -> running = false
            }
        }
    }
    return sentCount
}
