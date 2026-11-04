package org.fossify.messages.inkpilot.runtime

import org.fossify.messages.inkpilot.data.StudioContact

/** Model-facing projection, shared by the host adapter and its JVM tests.
 * Intake uses a recipient number as the name when no actual name is known.
 * Suppress all phone-shaped names, including local/country-prefix aliases, without
 * treating those aliases as equivalent contacts or changing persisted identity.
 */
internal fun toolContactText(contact: StudioContact): String {
    val name = contact.displayName.trim()
    val phoneShaped = name.any { it.isDigit() } && name.all {
        it.isDigit() || it.isWhitespace() || it == '\u00a0' || it in "+().-"
    }
    val label = if (name.isEmpty() || phoneShaped) "Customer" else name
    return label.take(160) + "\n" + contact.memory.take(1800)
}
