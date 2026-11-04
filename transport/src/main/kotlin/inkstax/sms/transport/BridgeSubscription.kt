package inkstax.sms.transport

/** One caller-supplied view of the current default SMS subscription and active SIMs. */
data class BridgeSubscriptionSnapshot(val defaultId: Int, val activeIds: Set<Int>)

/** Pin one active SIM for both the native receipt and explicit radio manager.
 * The final snapshot check rejects a changed default or removed SIM. A change
 * after that check cannot redirect submission: dispatch receives the pinned id.
 */
class BridgeSubscriptionSelection private constructor(
    val subscriptionId: Int,
    private val snapshot: () -> BridgeSubscriptionSnapshot
) {
    fun dispatchIfCurrent(dispatch: (Int) -> Unit): Boolean {
        val current = runCatching { snapshot() }.getOrNull() ?: return false
        if (current.defaultId != subscriptionId || subscriptionId !in current.activeIds) return false
        dispatch(subscriptionId)
        return true
    }

    companion object {
        fun capture(snapshot: () -> BridgeSubscriptionSnapshot): BridgeSubscriptionSelection? {
            val current = runCatching { snapshot() }.getOrNull() ?: return null
            val id = current.defaultId
            if (id < 0 || id == Int.MAX_VALUE || id !in current.activeIds) return null
            return BridgeSubscriptionSelection(id, snapshot)
        }
    }
}
