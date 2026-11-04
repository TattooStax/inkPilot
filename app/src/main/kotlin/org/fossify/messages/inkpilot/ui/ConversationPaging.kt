package org.fossify.messages.inkpilot.ui

import org.fossify.messages.messaging.MessageOrigin

internal data class MessageBoundary(val selection: String, val args: List<String>)

/** Both tables share (milliseconds, origin, provider ID) descending, even inside one MMS second. */
internal fun messageBoundary(after: MessageCursor?, origin: MessageOrigin): MessageBoundary {
    if (after == null) return MessageBoundary("", emptyList())
    val stamp = if (origin == MessageOrigin.MMS) "(date * 1000)" else "date"
    val tie = when {
        origin.ordinal < after.key.origin.ordinal -> "1"
        origin.ordinal > after.key.origin.ordinal -> "0"
        else -> "_id < ?"
    }
    val args = mutableListOf(after.dateMillis.toString(), after.dateMillis.toString())
    if (origin == after.key.origin) args += after.key.id.toString()
    return MessageBoundary(" AND ($stamp < ? OR ($stamp = ? AND ($tie)))", args)
}
