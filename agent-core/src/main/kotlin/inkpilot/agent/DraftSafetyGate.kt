package inkpilot.agent

/** Optional pre-persistence screen. A clean result does not prove the text is safe. */
fun interface DraftSafetyGate {
    fun allows(reply: String): Boolean

    companion object {
        val NONE = DraftSafetyGate { true }
    }
}

/** Blocks obvious booking assertions when this runtime has no verified calendar result. */
object BookingClaimGate : DraftSafetyGate {
    private val confirmed = Regex(
        """\b(?:you(?:'re| are) booked|(?:your|the) (?:appointment|consultation) (?:is |has been )?(?:booked|confirmed|scheduled)|i(?:'ve| have) booked|we(?:'ve| have) booked|all set for (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val promise = Regex(
        """\b(?:let me|i(?:'ll| will| can)|we(?:'ll| will| can)) (?:get you confirmed|confirm (?:(?:your|the) (?:appointment|booking|consultation|slot|time|date)|with (?:maya|your artist|our artist|studio staff))|book|schedule|reserve)\b""",
        RegexOption.IGNORE_CASE,
    )

    override fun allows(reply: String): Boolean = !confirmed.containsMatchIn(reply) && !promise.containsMatchIn(reply)
}
