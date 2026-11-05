package org.fossify.messages.inkpilot.data

/** Shared by the export screen and manifest so the plaintext disclosure cannot drift. */
internal object StudioArchiveScope {
    const val DESCRIPTION = "Plaintext Studio export includes contacts, memory, appointments, " +
        "retained reference originals, and copies of received-message, held-draft, and send-attempt text. " +
        "Native SMS/MMS provider history and model files are excluded. Restore is not available yet."
}
