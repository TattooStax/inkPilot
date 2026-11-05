package org.fossify.messages.inkpilot.runtime

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Persist remote-input text before any SIM check or possible carrier side effect. Never replay. */
internal class DirectReplyPendingStore(context: Context) {
    private val prefs = context.getSharedPreferences("inkpilot-direct-replies", Context.MODE_PRIVATE)

    data class Held(val id: String, val threadId: Long?, val recipient: String, val body: String,
        val subscriptionId: Int?, val createdAt: Long)
    data class Cursor(val createdAt: Long, val id: String)
    data class Page(val entries: List<Held>, val next: Cursor?)

    fun hold(threadId: Long?, recipient: String, body: String, subscriptionId: Int?): Held {
        require((threadId == null || threadId > 0) && recipient.isNotBlank() && body.isNotBlank())
        val item = Held(UUID.randomUUID().toString(), threadId, recipient, body, subscriptionId,
            System.currentTimeMillis())
        val json = JSONObject().put("thread", threadId ?: JSONObject.NULL).put("recipient", recipient).put("body", body)
            .put("subscription", subscriptionId ?: -1).put("created_at", item.createdAt).toString()
        check(prefs.edit().putString(item.id, json).commit()) { "Could not preserve direct reply" }
        return item
    }

    fun complete(id: String) {
        check(prefs.edit().remove(id).commit()) { "Could not clear accepted direct reply" }
    }

    fun held(): List<Held> = prefs.all.mapNotNull { (id, value) ->
        runCatching {
            val json = JSONObject(value as String)
            Held(id, json.optLong("thread", 0).takeIf { it > 0 }, json.getString("recipient"), json.getString("body"),
                json.getInt("subscription").takeIf { it >= 0 }, json.optLong("created_at", 0))
        }.getOrNull()
    }

    fun page(before: Cursor? = null, limit: Int = 50): Page {
        require(limit in 1..100)
        val ordered = held().sortedWith(compareByDescending<Held> { it.createdAt }.thenByDescending { it.id })
            .filter { before == null || it.createdAt < before.createdAt ||
                (it.createdAt == before.createdAt && it.id < before.id) }
        val visible = ordered.take(limit)
        val next = if (ordered.size > limit) visible.last().let { Cursor(it.createdAt, it.id) } else null
        return Page(visible, next)
    }
}
