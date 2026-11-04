package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous commit before dispatch: preserves human text, recipient, SIM and URI identity. */
class ComposerStore(context: Context) {
    private val lockFile = java.io.File(context.filesDir, "composer-dispatch.lock")
    internal fun <T> locked(block: () -> T): T = synchronized(processLock) {
        java.io.RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }
    fun getOrCreate(request: OutgoingMessage): OutgoingMessage = locked {
        load(request.threadId) ?: request.also { check(save(it)) }
    }
    private val claims = java.io.File(context.filesDir, "composer-claims").apply { mkdirs() }
    private fun attemptFile(id: String) = java.io.File(claims, "attempt-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) })
    private fun pendingFile(threadId: Long) = java.io.File(claims, "pending-$threadId")
    internal fun wasSubmitted(request: OutgoingMessage): Boolean = attemptFile(request.attemptId).exists()
    private fun durableMarker(file: java.io.File) {
        java.io.FileOutputStream(file).use { it.write(1); it.fd.sync() }
    }
    internal fun claim(request: OutgoingMessage): Boolean {
        if (isUnknown(request.threadId) || wasSubmitted(request)) return false
        durableMarker(attemptFile(request.attemptId))
        durableMarker(pendingFile(request.threadId))
        return true
    }
    internal fun finish(request: OutgoingMessage, outcome: DispatchOutcome) {
        when (outcome) {
            is DispatchOutcome.Accepted -> {
                check(prefs.edit().remove(request.threadId.toString()).remove("unknown:${request.threadId}").commit())
                check(pendingFile(request.threadId).delete())
            }
            is DispatchOutcome.Rejected -> {
                check(attemptFile(request.attemptId).delete())
                check(pendingFile(request.threadId).delete())
            }
            is DispatchOutcome.Unknown -> Unit // claim survives restart; no replay
        }
    }
    private val prefs = context.getSharedPreferences("durable_composers", Context.MODE_PRIVATE)
    fun save(request: OutgoingMessage): Boolean = prefs.edit().putString(request.threadId.toString(), JSONObject()
        .put("attemptId", request.attemptId).put("recipient", request.recipient).put("body", request.body).put("subscriptionId", request.subscriptionId)
        .put("uris", JSONArray(request.attachmentUris.map { it.toString() })).toString()).commit()
    fun load(threadId: Long): OutgoingMessage? = prefs.getString(threadId.toString(), null)?.let {
        val json = JSONObject(it)
        val uris = json.getJSONArray("uris")
        OutgoingMessage(threadId, json.getString("recipient"), json.getString("body"),
            (0 until uris.length()).map { index -> Uri.parse(uris.getString(index)) }, json.getInt("subscriptionId"), json.optString("attemptId", "legacy:$threadId"))
    }
    fun complete(threadId: Long, outcome: DispatchOutcome) {
        when (outcome) {
            is DispatchOutcome.Accepted -> clear(threadId)
            is DispatchOutcome.Unknown -> check(prefs.edit().putString("unknown:$threadId", outcome.claimId).commit())
            is DispatchOutcome.Rejected -> Unit
        }
    }
    fun isUnknown(threadId: Long): Boolean = pendingFile(threadId).exists() || prefs.contains("unknown:$threadId")
    fun clear(threadId: Long) { pendingFile(threadId).delete(); check(prefs.edit().remove(threadId.toString()).remove("unknown:$threadId").commit()) }
    companion object { private val processLock = Any() }
}
