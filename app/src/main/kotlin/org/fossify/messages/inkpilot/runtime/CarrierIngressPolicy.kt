package org.fossify.messages.inkpilot.runtime

/** IDs from Android's providers and SIM manager are opaque, never list positions. */
internal object CarrierIngressPolicy {
    fun nativeSource(providerId: Long, threadId: Long): String? =
        if (providerId > 0 && threadId > 0) "sms:$providerId" else null

    fun exactSubscription(preferredId: Int?, activeIds: Set<Int>): Int? = when {
        preferredId != null -> preferredId.takeIf(activeIds::contains)
        activeIds.size == 1 -> activeIds.single()
        else -> null
    }

    fun mmsProviderId(scheme: String?, authority: String?, segments: List<String>): Long? {
        if (scheme != "content" || authority != "mms") return null
        val id = when {
            segments.size == 1 -> segments[0]
            segments.size == 2 && segments[0] == "inbox" -> segments[1]
            else -> return null
        }
        return id.toLongOrNull()?.takeIf { it > 0 }
    }

    data class ReplyBinding(val threadId: Long, val origin: String, val messageId: Long)

    fun replyBinding(data: String?): ReplyBinding? {
        val parts = data?.split('/') ?: return null
        if (parts.size != 6 || parts[0] != "inkpilot-reply:" || parts[2] != "thread") return null
        val thread = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val origin = parts[4].takeIf { it == "sms" || it == "mms" } ?: return null
        val message = parts[5].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ReplyBinding(thread, origin, message)
    }

    fun respondRecipient(scheme: String?, recipient: String?): String? {
        if (scheme !in setOf("sms", "smsto", "mms", "mmsto")) return null
        val value = recipient?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        return value.takeUnless { it.any { char -> char == ',' || char == ';' || char == '?' || char == '#' || char == '/' } }
    }
}
