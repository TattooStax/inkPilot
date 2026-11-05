package org.fossify.messages.messaging

import org.fossify.messages.models.Message

/** Provider tables and local scheduled rows have independent ID namespaces. */
enum class MessageOrigin { SMS, MMS, SCHEDULED_SMS, SCHEDULED_MMS }

data class MessageKey(val origin: MessageOrigin, val id: Long) {
    // Two origin bits leave 59 row-ID bits in the existing 61-bit thread key.
    internal fun stableIdKey(): Long = (id shl 2) or origin.ordinal.toLong()

    // The Int selection API reserves two origin bits, keeping namespaces disjoint.
    internal fun selectionKey(): Int = ((id xor (id ushr Int.SIZE_BITS)).toInt() shl 2) or origin.ordinal
}

fun Message.key(): MessageKey = MessageKey(
    origin = when {
        isScheduled && isMMS -> MessageOrigin.SCHEDULED_MMS
        isScheduled -> MessageOrigin.SCHEDULED_SMS
        isMMS -> MessageOrigin.MMS
        else -> MessageOrigin.SMS
    },
    id = id
)
