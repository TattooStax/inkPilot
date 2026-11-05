package org.fossify.messages.messaging

import org.fossify.messages.models.SIMCard
import org.junit.Assert.*
import org.junit.Test

class ComposerSimSelectionUnitTest {
    private val cards = listOf(SIMCard(1, 31, "SIM A"), SIMCard(2, 72, "SIM B"))
    @Test fun restoreBeforeSetupOverridesPreferenceAndDisplay() {
        val selection = ComposerSimSelection()
        selection.restore(72)
        selection.update(cards, 31)
        assertEquals(72, selection.subscriptionId)
        assertEquals("2", selection.label)
    }
    @Test fun restoreAfterSetupOverridesPreferenceAndDisplay() {
        val selection = ComposerSimSelection()
        selection.update(cards, 31)
        selection.restore(72)
        assertEquals(72, selection.subscriptionId)
        assertEquals("2", selection.label)
        selection.update(cards, 31)
        assertEquals(72, selection.subscriptionId)
        assertEquals("2", selection.label)
    }
    @Test fun singleSimShowsItsExactSubscriptionAndDoesNotSwitch() {
        val selection = ComposerSimSelection()
        selection.restore(72)
        selection.update(cards.drop(1).map { it.copy(id = 1) }, 72)
        selection.selectNext()
        assertEquals(72, selection.subscriptionId)
        assertEquals("1", selection.label)
    }
    @Test fun missingRestoredSimIsVisibleAsUnavailableUntilUserSelects() {
        val selection = ComposerSimSelection()
        selection.restore(72)
        selection.update(cards.take(1), 31)
        assertNull(selection.selectedCard)
        assertEquals("?", selection.label)
        assertEquals(72, selection.subscriptionId)
        selection.selectNext()
        assertEquals("1", selection.label)
        assertEquals(31, selection.subscriptionId)
    }
}
