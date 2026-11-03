package org.fossify.messages.transport

/**
 * The one lock for a whole outbound pickup run: a run picks work
 * up from the brain and sends it, one item at a time, and two
 * runs at once could pick the same queued item up twice before
 * either send's verdict is filed — the brain hands an item over
 * once, but a second run asking in the same window is a second
 * asking, and this lock is what makes the runs take turns
 * instead. It is process-wide so it holds across every trigger
 * of a run: the scheduled pickup, a pairing's first pickup, and
 * any later caller's enqueue all land in the one worker, and
 * the worker holds this lock for its whole run.
 *
 * The lock is held across the run's network calls and sends on
 * purpose — serializing only the storage around them would
 * leave the pick-up-then-send window itself unguarded, which is
 * the window that matters. No other bridge lock is taken while
 * holding this one except the guards a send itself takes, in
 * their own order, so this lock is always the outermost lock a
 * pickup run holds.
 */
internal object OutboundPickupGuard {
    val lock = Any()
}
