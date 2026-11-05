package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase
import inkpilot.agent.DraftEvidence
import inkpilot.agent.DraftRequest

/**
 * The current event, reviewed memory and studio guide are read from one
 * database snapshot. Neither free-text field becomes a verified fact.
 */
fun StudioRepository.readEvidence(request: DraftRequest): DraftEvidence? = database.studioDraftEvidence(request)

internal fun SQLiteDatabase.studioDraftEvidence(request: DraftRequest): DraftEvidence? = transaction {
    val source = studioSource(request.eventId) ?: return@transaction null
    if (source.eventId != request.eventId || source.contactId != request.contactId ||
        source.threadId != request.threadId) return@transaction null
    DraftEvidence(request, source, studioMemory(source.contactId), studioSoul())
}
