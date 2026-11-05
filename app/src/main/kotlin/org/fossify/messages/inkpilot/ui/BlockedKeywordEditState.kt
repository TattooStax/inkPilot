package org.fossify.messages.inkpilot.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog

/** Keeps a staff keyword edit through shell recreation without committing partial text. */
internal class BlockedKeywordEditState(saved: Bundle? = null) {
    private var active = saved?.getBoolean("active") ?: false
    private var original = saved?.getString("original")
    private var draft = saved?.getString("draft").orEmpty()
    private var restorePending = active
    private var dialog: AlertDialog? = null

    fun begin(value: String?, text: String) {
        active = true
        original = value
        draft = text
        restorePending = false
    }
    fun update(text: String) { if (active) draft = text }
    fun takeRestoration(): Pair<String?, String>? {
        if (!restorePending) return null
        restorePending = false
        return original to draft
    }
    fun deferRestoration() { restorePending = active }
    fun attach(value: AlertDialog) { dialog = value }
    fun clear() {
        active = false
        restorePending = false
        dialog = null
    }
    fun suspendUntilUnlock() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        restorePending = active
    }
    fun close() { dialog?.dismiss() }
    fun save(): Bundle? = if (!active) null else Bundle().apply {
        putBoolean("active", true)
        putString("original", original)
        putString("draft", draft)
    }
}
