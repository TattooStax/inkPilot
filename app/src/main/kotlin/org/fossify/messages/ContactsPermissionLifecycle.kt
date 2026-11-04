package org.fossify.messages

/** Permission signals are hints; failed registration leaves the next signal free to retry. */
internal class ContactsPermissionLifecycle {
    var registered = false
        private set

    fun reconcile(allowed: Boolean, start: () -> Unit, stop: () -> Unit) {
        if (allowed == registered) return
        stop()
        registered = false
        if (allowed) {
            try {
                start()
                registered = true
            } catch (_: SecurityException) {
                stop()
            }
        }
    }
}
