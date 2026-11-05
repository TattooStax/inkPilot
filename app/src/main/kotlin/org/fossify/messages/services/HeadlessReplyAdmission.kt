package org.fossify.messages.services

import org.fossify.messages.inkpilot.runtime.CarrierIngressPolicy

/**
 * Keep human text even if provider or preference lookup fails. A failed preference
 * read is different from an absent preference, which may use a single active SIM.
 */
internal object HeadlessReplyAdmission {
    sealed interface SavedSim {
        data class Selected(val id: Int) : SavedSim
        data object Unspecified : SavedSim
        data object LookupFailed : SavedSim
    }

    data class Admission<T>(val held: T, val savedSim: SavedSim) {
        fun exactSubscription(activeIds: Set<Int>): Int? = when (savedSim) {
            is SavedSim.Selected -> CarrierIngressPolicy.exactSubscription(savedSim.id, activeIds)
            SavedSim.Unspecified -> CarrierIngressPolicy.exactSubscription(null, activeIds)
            SavedSim.LookupFailed -> null
        }
    }

    fun <T> preserve(
        resolveThread: () -> Long,
        resolveSubscription: () -> Int?,
        persist: (Long?, Int?) -> T,
    ): Admission<T> {
        val thread = runCatching { resolveThread().takeIf { it > 0 } }.getOrNull()
        val savedSim = try {
            resolveSubscription()?.let(SavedSim::Selected) ?: SavedSim.Unspecified
        } catch (_: Exception) {
            SavedSim.LookupFailed
        }
        val selected = (savedSim as? SavedSim.Selected)?.id
        return Admission(persist(thread, selected), savedSim)
    }
}
