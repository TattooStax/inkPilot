package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** Synchronous commit before dispatch: preserves human text, recipient, SIM and URI identity. */
class ComposerStore(context: Context) {
    internal val appContext = context.applicationContext
    private val lockFile = java.io.File(context.filesDir, "composer-dispatch.lock")
    internal fun <T> locked(block: () -> T): T = synchronized(processLock) {
        java.io.RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }
    fun getOrCreate(request: OutgoingMessage): OutgoingMessage = locked {
        if (hasCompletedCompose(request.threadId)) request.copy(body = "", attachmentUris = emptyList())
        else load(request.threadId) ?: request.also { check(saveUnclaimed(it)) }
    }
    internal val claims = java.io.File(context.filesDir, "composer-claims").apply { mkdirs() }
    private fun attemptFile(id: String) = java.io.File(claims, "attempt-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) })
    internal fun pendingFile(threadId: Long) = java.io.File(claims, "pending-$threadId")
    internal fun wasSubmitted(request: OutgoingMessage): Boolean = attemptFile(request.attemptId).exists()
    internal fun durableMarker(file: java.io.File) {
        java.io.FileOutputStream(file).use { it.write(1); it.fd.sync() }
    }
    internal var claimMarker: (java.io.File) -> Unit = { durableMarker(it) }
    internal fun claim(request: OutgoingMessage): Boolean {
        if (isUnknown(request.threadId) || wasSubmitted(request)) return false
        claimMarker(attemptFile(request.attemptId))
        claimMarker(pendingFile(request.threadId))
        return true
    }
    internal fun finish(request: OutgoingMessage, outcome: DispatchOutcome) {
        when (outcome) {
            is DispatchOutcome.Accepted -> {
                markComposeCompleted(request.threadId)
                // Keep the saved URI until its private copy is removed. A crash must not
                // erase the only reference before cleanup after an accepted handoff.
                appContext.discardSharedComposerImages(request.attachmentUris)
                check(prefs.edit().remove(request.threadId.toString()).remove("unknown:${request.threadId}")
                    .remove("mms-failed:${request.threadId}").commit())
                check(pendingFile(request.threadId).delete())
            }
            is DispatchOutcome.Rejected -> {
                check(attemptFile(request.attemptId).delete())
                check(pendingFile(request.threadId).delete())
            }
            is DispatchOutcome.Unknown -> Unit // claim survives restart; no replay
        }
    }
    internal val prefs = context.getSharedPreferences("durable_composers", Context.MODE_PRIVATE)
    init { locked { recoverMmsConfirmations(); recoverSharedMediaAfterCrash() } }
    private fun recoverSharedMediaAfterCrash() {
        val requests = try {
            prefs.all.keys.mapNotNull { it.toLongOrNull() }.mapNotNull(::load)
        } catch (_: Exception) { return } // An unreadable draft must never permit copy deletion.
        val referenced = requests.flatMap(OutgoingMessage::attachmentUris)
            .mapNotNull(appContext::ownedSharedComposerName).toSet()
        appContext.recoverSharedComposerImages(referenced)
    }
    fun save(request: OutgoingMessage): Boolean = locked { saveUnclaimed(request) }
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
    // The attempt marker commits first. If writing the pending marker fails, that
    // attempt must still block replay and be visible for staff review after restart.
    fun isUnknown(threadId: Long): Boolean = hasCompletedCompose(threadId) ||
        pendingFile(threadId).exists() || prefs.contains("unknown:$threadId") ||
        load(threadId)?.let(::wasSubmitted) == true
    fun clear(threadId: Long) {
        val media = load(threadId)?.attachmentUris.orEmpty()
        pendingFile(threadId).delete()
        check(prefs.edit().remove(threadId.toString()).remove("unknown:$threadId")
            .remove("mms-failed:$threadId").remove("mms-confirmed:$threadId").commit())
        appContext.discardSharedComposerImages(media)
    }
    companion object { private val processLock = Any() }
}
