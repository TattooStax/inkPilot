package org.fossify.messages.messaging

/** 0 = waiting, 1 = confirmed, 2 = rejected. Partial success is never retryable. */
internal fun partVerdict(outcomes: List<Int>, expected: Int): String = when {
    outcomes.size < expected || 0 in outcomes -> if (2 in outcomes) "UNKNOWN" else "DISPATCHING"
    outcomes.all { it == 1 } -> "CONFIRMED"
    outcomes.all { it == 2 } -> "FAILED"
    else -> "UNKNOWN"
}
