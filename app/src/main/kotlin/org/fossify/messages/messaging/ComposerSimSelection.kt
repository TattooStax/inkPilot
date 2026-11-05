package org.fossify.messages.messaging

import org.fossify.messages.models.SIMCard

/** A single subscription identity drives both the visible selector and dispatch. */
class ComposerSimSelection {
    var cards: List<SIMCard> = emptyList()
        private set
    private var preferredId: Int? = null
    private var explicitId: Int? = null
    val subscriptionId: Int get() = explicitId ?: preferredId ?: -1
    val selectedCard: SIMCard? get() = cards.firstOrNull { it.subscriptionId == subscriptionId }
    val label: String get() = selectedCard?.id?.toString() ?: "?"
    fun restore(subscriptionId: Int) { explicitId = subscriptionId }
    fun update(cards: List<SIMCard>, preferredId: Int?) {
        this.cards = cards.toList()
        this.preferredId = preferredId
    }
    fun selectNext() {
        if (cards.isEmpty()) return
        val index = cards.indexOfFirst { it.subscriptionId == subscriptionId }
        explicitId = cards[(index + 1) % cards.size].subscriptionId
    }
}
