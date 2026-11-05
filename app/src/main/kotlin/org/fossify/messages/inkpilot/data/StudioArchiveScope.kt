package org.fossify.messages.inkpilot.data

/** Shared by the export screen and manifest so the plaintext disclosure cannot drift. */
internal object StudioArchiveScope {
    const val DESCRIPTION = "Plaintext Studio export includes the entire Studio database: contacts, memory, " +
        "agent guide and settings, appointments, and copies of received-message, held-draft, and send-attempt text; " +
        "it also includes retained reference originals. " +
        "Native SMS/MMS provider history and model files are excluded. Restore is not available yet."
}
