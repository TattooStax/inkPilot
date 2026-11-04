package org.fossify.messages.inkpilot.runtime

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Persist remote-input text before any SIM check or possible carrier side effect. Never replay. */
internal class DirectReplyPendingStore(context: Context) {
    private val prefs = context.getSharedPreferences("inkpilot-direct-replies", Context.MODE_PRIVATE)

    data class Held(val id: String, val threadId: Long, val recipient: String, val body: String, val subscriptionId: Int?)

    fun hold(threadId: Long, recipient: String, body: String, subscriptionId: Int?): Held {
        require(threadId > 0 && recipient.isNotBlank() && body.isNotBlank())
        val item = Held(UUID.randomUUID().toString(), threadId, recipient, body, subscriptionId)
        val json = JSONObject().put("thread", threadId).put("recipient", recipient).put("body", body)
            .put("subscription", subscriptionId ?: -1).toString()
        check(prefs.edit().putString(item.id, json).commit()) { "Could not preserve direct reply" }
        return item
    }

    fun complete(id: String) {
        check(prefs.edit().remove(id).commit()) { "Could not clear accepted direct reply" }
    }

    fun held(): List<Held> = prefs.all.mapNotNull { (id, value) ->
        runCatching {
            val json = JSONObject(value as String)
            Held(id, json.getLong("thread"), json.getString("recipient"), json.getString("body"),
                json.getInt("subscription").takeIf { it >= 0 })
        }.getOrNull()
    }
}
