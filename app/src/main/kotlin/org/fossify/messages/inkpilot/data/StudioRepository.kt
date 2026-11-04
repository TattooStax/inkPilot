package org.fossify.messages.inkpilot.data

import android.content.Context
import inkpilot.agent.ContactMemory
import inkpilot.agent.ContactMemoryReader
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftSource
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.HeldDraftReceipt
import inkpilot.agent.HeldDraftStore
import inkpilot.agent.HeldDraftWrite
import java.io.Closeable

/** Synchronous database operations; callers must use a background thread. */
class StudioRepository(context: Context, databaseName: String = DATABASE_NAME) :
    HeldDraftStore, ContactMemoryReader, Closeable {
    internal val applicationContext: Context = context.applicationContext
    private val helper = StudioDatabase(applicationContext, databaseName)
    internal val database get() = helper.getWritableDatabase()
    private val db get() = database

    fun recordInbound(
        eventId: String, threadId: Long, number: String, body: String, displayName: String,
        receivedAt: Long, subscriptionId: Int,
    ): DraftRequest = db.recordStudioInbound(eventId, threadId, number, body, displayName, receivedAt, subscriptionId)

    /** Authorizes a new held generation; never submits a message. */
    fun regenerate(eventId: String, expectedRevision: Long): DraftRegenerationOutcome =
        db.regenerateStudioDraft(eventId, expectedRevision)

    fun intakeCheckpoint(): Long = db.rawQuery("SELECT provider_id FROM intake_checkpoint WHERE id = 1", null).use {
        check(it.moveToFirst()); it.getLong(0)
    }

    fun advanceIntakeCheckpoint(providerId: Long) {
        require(providerId >= 0)
        db.execSQL("UPDATE intake_checkpoint SET provider_id = MAX(provider_id, ?) WHERE id = 1", arrayOf(providerId))
    }

    fun cancelWorker(token: String) {
        require(token.isNotBlank())
        db.execSQL("INSERT OR IGNORE INTO worker_cancellations(token) VALUES(?)", arrayOf(token))
    }

    fun holdForWorker(token: String, write: HeldDraftWrite): DraftStoreResult = db.transaction {
        val stopped = db.rawQuery("SELECT 1 FROM worker_cancellations WHERE token = ?", arrayOf(token)).use { it.moveToFirst() }
        if (stopped) DraftStoreResult.SourceChanged else db.holdStudioDraft(write)
    }

    fun pendingRequests(limit: Int = 100): List<DraftRequest> = db.pendingStudioRequests(limit)

    fun recordDraftAttempt(eventId: String, sourceRevision: Long, reason: String): Boolean =
        db.recordStudioDraftAttempt(eventId, sourceRevision, reason)

    fun clearDraftAttempts() { db.delete("draft_attempts", null, null) }

    fun contacts(): List<StudioContact> = db.studioContacts()

    fun contact(contactId: String): StudioContact? = db.studioContact(contactId)

    fun upsertContact(number: String, displayName: String): StudioContact = db.upsertStudioContact(number, displayName)

    fun saveMemory(contactId: String, text: String) = db.saveStudioMemory(contactId, text)

    fun saveContactEdit(contactId: String, text: String, paused: Boolean, source: String) =
        db.saveStudioContactEdit(contactId, text, paused, source)

    fun memoryEntry(contactId: String): MemoryEntry? = db.studioMemoryEntry(contactId)

    fun resolveContact(address: String, region: String): ContactMatch = db.resolveStudioContact(address, region)

    fun reviewAlias(address: String, region: String, contactId: String) {
        val normalized = canonicalStudioNumber(address)
        require(region.length <= 8)
        db.transaction {
            requireNotNull(db.studioContact(contactId))
            db.execSQL("""UPDATE studio_contacts SET revision = revision + 1 WHERE number = ? OR id IN (
                SELECT contact_id FROM contact_aliases WHERE address = ? AND region = ?
            )""", arrayOf(normalized, normalized, region.uppercase()))
            db.execSQL("DELETE FROM contact_aliases WHERE address = ? AND region = ?", arrayOf(normalized, region.uppercase()))
            db.execSQL("INSERT INTO contact_aliases VALUES(?, ?, ?, 'REVIEWED')", arrayOf(normalized, region.uppercase(), contactId))
            db.execSQL("UPDATE studio_contacts SET revision = revision + 1 WHERE id = ?", arrayOf(contactId))
        }
    }

    fun forgetMemory(contactId: String) = db.transaction {
        db.saveStudioContactEdit(contactId, "", requireNotNull(db.studioContact(contactId)).paused, "Staff removal")
    }

    fun exportMemory(contactId: String): String = memoryEntry(contactId)?.let {
        "Source: ${it.source}\nReview: ${it.reviewState}\n${it.body}"
    }.orEmpty()

    fun setPaused(contactId: String, paused: Boolean) = db.setStudioPaused(contactId, paused)

    fun heldDrafts(): List<ReviewDraft> = db.reviewStudioDrafts()

    fun drafts(limit: Int = 100): List<ReviewDraft> = db.reviewStudioDrafts(heldOnly = false, limit = limit)

    fun draftsForThread(threadId: Long): List<ReviewDraft> = db.reviewStudioDrafts(threadId = threadId)

    fun draft(draftId: String): ReviewDraft? = db.reviewStudioDrafts(heldOnly = false, limit = 1, draftId = draftId).firstOrNull()

    fun dismissDraft(draftId: String): Boolean = db.dismissStudioDraft(draftId)

    fun invalidateAllPending() { db.execSQL("UPDATE studio_contacts SET revision = revision + 1") }

    fun isDraftingEnabled(): Boolean = db.studioDraftingEnabled()

    fun setDraftingEnabled(enabled: Boolean) = db.setStudioDraftingEnabled(enabled)

    fun soul(): String = db.studioSoul()

    fun setSoul(text: String) = db.setStudioSoul(text)

    fun claimDraftForSend(
        draftId: String, expectedBody: String, editedBody: String, subscriptionId: Int,
    ): SendClaim? = db.claimStudioDraft(draftId, expectedBody, editedBody, subscriptionId)

    fun getSendClaim(draftId: String): SendClaim? = db.studioSendClaim(draftId)

    fun attachProviderId(draftId: String, providerId: Long): Boolean = db.attachStudioProviderId(draftId, providerId)

    fun pendingProviderUpdates(limit: Int = 100): List<SendClaim> = db.pendingStudioProviderUpdates(limit)

    fun markProviderSynced(draftId: String, providerId: Long): Boolean = db.markStudioProviderSynced(draftId, providerId)

    fun markSendResult(
        draftId: String, status: SendStatus, providerId: Long? = null, error: String? = null,
    ): Boolean = db.finishStudioSend(draftId, status, providerId, error)

    fun prepareSendParts(draftId: String, count: Int): Boolean = db.prepareStudioSendParts(draftId, count)

    fun recordSendPart(draftId: String, partIndex: Int, success: Boolean, error: String? = null): SendStatus? =
        db.recordStudioSendPart(draftId, partIndex, success, error)

    override fun readSource(eventId: String): DraftSource? = db.studioSource(eventId)

    override fun findHeldDraft(key: DraftKey): HeldDraftReceipt? = db.studioReceipt(key)

    override fun holdIfCurrent(write: HeldDraftWrite): DraftStoreResult = db.holdStudioDraft(write)

    override fun read(contactId: String): List<ContactMemory> = db.studioMemory(contactId)

    override fun close() = helper.close()

    companion object {
        const val DATABASE_NAME = "inkpilot-studio.db"
    }
}
