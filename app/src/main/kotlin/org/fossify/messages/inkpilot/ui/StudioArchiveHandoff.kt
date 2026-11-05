package org.fossify.messages.inkpilot.ui

import android.net.Uri

/** A picker result cannot unlock the app or authorize a background export. */
internal object StudioArchiveHandoff {
    enum class Outcome { CANCELED, STARTED, LOCKED_DISCARDED, LOCKED_UNDELETED }

    fun handle(uri: Uri?, locked: Boolean, discard: (Uri) -> Boolean, start: (Uri) -> Unit): Outcome {
        if (uri == null) return Outcome.CANCELED
        if (locked) {
            val removed = runCatching { discard(uri) }.getOrDefault(false)
            return if (removed) Outcome.LOCKED_DISCARDED else Outcome.LOCKED_UNDELETED
        }
        start(uri)
        return Outcome.STARTED
    }
}
