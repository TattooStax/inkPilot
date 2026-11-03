package inkpilot.inference

/** Conservative CPU budgets; context counts tokens, reply length is checked by agent-core. */
data class InferenceLimits(
    val contextTokens: Int = 2048,
    val maximumOutputTokens: Int = 256,
    val deadlineMillis: Long = 120_000,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
) {
    init {
        require(contextTokens in 512..4096)
        require(maximumOutputTokens in 16..512 && maximumOutputTokens < contextTokens)
        require(deadlineMillis in 1_000..180_000)
        require(threads in 1..4)
    }
}
