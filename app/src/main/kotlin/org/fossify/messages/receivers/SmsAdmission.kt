package org.fossify.messages.receivers

/** Filter decisions are advisory until a durable private receipt exists. */
internal object SmsAdmission {
    enum class Result { DELIVERED, FILTERED, QUARANTINED_NATIVE }

    fun process(
        classify: () -> String?,
        journal: (String) -> Unit,
        normalNative: () -> Unit,
        quarantinedNative: () -> Unit,
        onUncertain: () -> Unit,
    ): Result {
        val reason = try { classify() } catch (_: Exception) {
            onUncertain()
            quarantinedNative()
            return Result.QUARANTINED_NATIVE
        }
        if (reason == null) {
            normalNative()
            return Result.DELIVERED
        }
        try { journal(reason) } catch (_: Exception) {
            onUncertain()
            quarantinedNative()
            return Result.QUARANTINED_NATIVE
        }
        return Result.FILTERED
    }
}
