package inkstax.sms.transport

/** A zero-row update settles only a verified deletion, never an unreadable row.
 * Bindings supply presence: true for existing, false for absent, null for
 * unavailable. Positive writes need no second read; negative counts fail.
 */
fun bridgeReceiptUpdateSettles(updated: Int, receiptExists: () -> Boolean?): Boolean {
    if (updated > 0) return true
    if (updated != 0) return false
    return runCatching { receiptExists() == false }.getOrDefault(false)
}
