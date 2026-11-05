package org.fossify.messages.inkpilot.data

import android.content.Context

/**
 * A generic warning, deliberately without a URI or customer data. SAF providers may refuse to
 * delete a partial plaintext export. Keep the warning until staff acknowledge it after unlock.
 */
internal class StudioArchiveCleanupNotice(context: Context, name: String = "studio_archive_cleanup") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun recordIfDeletionFails(delete: () -> Boolean): Boolean = synchronized(lock) {
        val deleted = runCatching(delete).getOrDefault(false)
        if (!deleted) {
            val generation = preferences.getLong(LAST_GENERATION, 0L) + 1L
            check(preferences.edit().putLong(LAST_GENERATION, generation)
                .putLong(PENDING_GENERATION, generation).commit()) {
                "Could not save Studio export cleanup warning."
            }
        }
        deleted
    }

    fun pendingGenerationIfUnlocked(unlocked: Boolean): Long? = synchronized(lock) {
        if (!unlocked) return@synchronized null
        preferences.getLong(PENDING_GENERATION, 0L).takeIf { it > 0L }
    }

    /** A newer failed export must not be cleared by an older dialog acknowledgement. */
    fun acknowledgeIfUnlocked(unlocked: Boolean, generation: Long): Boolean = synchronized(lock) {
        if (!unlocked || generation <= 0L || preferences.getLong(PENDING_GENERATION, 0L) != generation) {
            return@synchronized false
        }
        preferences.edit().remove(PENDING_GENERATION).commit()
    }

    companion object {
        private const val LAST_GENERATION = "last_generation"
        private const val PENDING_GENERATION = "pending_generation"
        private val lock = Any()
    }
}
