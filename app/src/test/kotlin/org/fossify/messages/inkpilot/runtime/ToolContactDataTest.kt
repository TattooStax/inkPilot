package org.fossify.messages.inkpilot.runtime

import org.fossify.messages.inkpilot.data.StudioContact
import org.junit.Assert.*
import org.junit.Test

class ToolContactDataTest {
    private fun contact(name: String) = StudioContact(
        "synthetic-contact", "+15551234567", name, "Approved note", false, 2, "sms:1", 0,
    )

    @Test fun recoveredNumberFallbackNeverBecomesModelLabel() {
        val text = toolContactText(contact("+15551234567"))
        assertEquals("Customer\nApproved note", text)
        assertFalse(text.contains("15551234567"))
    }

    @Test fun formattedCanonicalAliasesRemainGeneric() {
        listOf(" +1 (555) 123-4567 ", "1.555.123.4567", "1555\u00a0123\u00a04567",
            "555-123-4567", "001-555-123-4567", "").forEach { alias ->
            assertEquals("Alias: $alias", "Customer\nApproved note", toolContactText(contact(alias)))
        }
    }

    @Test fun actualNamesAndStaffNotesSurvive() {
        listOf("Alice Smith", "Studio 54 client", "José", "A. Customer").forEach { name ->
            assertEquals("$name\nApproved note", toolContactText(contact(name)))
        }
    }
}
