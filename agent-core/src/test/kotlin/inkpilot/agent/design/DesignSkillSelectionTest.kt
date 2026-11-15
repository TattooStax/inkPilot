package inkpilot.agent.design

import org.junit.Assert.*
import org.junit.Test

class DesignSkillSelectionTest {
    @Test fun sleeveIncludesMeasuredLayoutAndTransfer() {
        assertEquals(listOf("tattoo-design-workflow","tattoo-sleeve-layout","tattoo-stencil-production"),DesignSkillSelection.select("Make a full sleeve"))
    }
    @Test fun portraitDoesNotLoadUnrelatedSleeveInstructions() {
        assertEquals(listOf("tattoo-design-workflow"),DesignSkillSelection.select("A flower portrait"))
    }
    @Test fun stencilUsesTransferSkillWithoutAssumingSleeve() {
        assertEquals(listOf("tattoo-design-workflow","tattoo-stencil-production"),DesignSkillSelection.select("Prepare a thermal outline"))
    }
    @Test fun artistStyleOnlyAppearsWhenSupplied() {
        val first = DesignSkillSelection.brief("A sleeve","Color traditional") { "workflow: $it" }
        val second = DesignSkillSelection.brief("A sleeve","") { "workflow: $it" }
        assertTrue(first.contains("Color traditional"));assertFalse(second.contains("Color traditional"))
        assertTrue(first.contains("draft, no artwork rendered"))
    }
    @Test(expected = IllegalArgumentException::class) fun blankRequestCannotProduceReadyBrief() {
        DesignSkillSelection.brief(" ","") { it }
    }
}
