package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous commit before dispatch: preserves human text, recipient, SIM and URI identity. */
class ComposerStore(context: Context) {
    private val prefs = context.getSharedPreferences("durable_composers", Context.MODE_PRIVATE)
    fun save(request: OutgoingMessage): Boolean = prefs.edit().putString(request.threadId.toString(), JSONObject()
        .put("recipient", request.recipient).put("body", request.body).put("subscriptionId", request.subscriptionId)
        .put("uris", JSONArray(request.attachmentUris.map { it.toString() })).toString()).commit()
    fun load(threadId: Long): OutgoingMessage? = prefs.getString(threadId.toString(), null)?.let {
        val json = JSONObject(it)
        val uris = json.getJSONArray("uris")
        OutgoingMessage(threadId, json.getString("recipient"), json.getString("body"),
            (0 until uris.length()).map { index -> Uri.parse(uris.getString(index)) }, json.getInt("subscriptionId"))
    }
    fun complete(threadId: Long, outcome: DispatchOutcome) {
        when (outcome) {
            is DispatchOutcome.Accepted -> clear(threadId)
            is DispatchOutcome.Unknown -> check(prefs.edit().putString("unknown:$threadId", outcome.claimId).commit())
            is DispatchOutcome.Rejected -> Unit
        }
    }
    fun clear(threadId: Long) { check(prefs.edit().remove(threadId.toString()).remove("unknown:$threadId").commit()) }
}
