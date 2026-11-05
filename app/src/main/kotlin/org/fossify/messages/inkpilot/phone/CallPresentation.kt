package org.fossify.messages.inkpilot.phone
/** Both notifications and call screen suppress identity while locked. */
internal object CallPresentation {
    fun identity(locked: Boolean, handle: String?): String =
        if (locked) "Phone call · unlock for caller details" else handle?.takeIf { it.isNotBlank() } ?: "Private caller"
}
