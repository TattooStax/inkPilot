package org.fossify.messages.inkpilot.ui

import android.os.Bundle

internal data class ContactEditDraft(val contactId: String, val memory: String,
    val paused: Boolean, val source: String)

/** Only the active staff editor is saved across Activity recreation. */
internal class ContactEditState(saved: Bundle? = null) {
    private var draft: ContactEditDraft? = saved?.let { bundle ->
        bundle.getString("contact")?.let { id -> ContactEditDraft(id,
            bundle.getString("memory").orEmpty(), bundle.getBoolean("paused"),
            bundle.getString("source").orEmpty()) }
    }
    private var restorePending = draft != null

    fun begin(value: ContactEditDraft) { draft = value; restorePending = false }
    fun update(value: ContactEditDraft) { draft = value }
    fun clear() { draft = null; restorePending = false }
    fun deferRestoration() { restorePending = draft != null }
    fun takeRestoration(): ContactEditDraft? = draft.takeIf { restorePending }.also { restorePending = false }
    fun save(): Bundle? = draft?.let { current -> Bundle().apply {
        putString("contact", current.contactId)
        putString("memory", current.memory)
        putBoolean("paused", current.paused)
        putString("source", current.source)
    } }
}
