package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import inkpilot.agent.DraftRequest
import inkpilot.agent.ProposalKind
import inkpilot.agent.ProposalPayload
import inkpilot.agent.ProposalValidation
import inkpilot.agent.ScopedProposalRequest
import inkpilot.agent.ToolProposals
import org.fossify.messages.inkpilot.studio.CalendarProposal
import org.fossify.messages.inkpilot.studio.ScheduleResult
import org.fossify.messages.inkpilot.studio.StudioCalendarStore
import java.util.UUID

data class StudioProposal(
    val id: String, val eventId: String, val kind: ProposalKind, val contactId: String,
    val threadId: String, val sourceRevision: Long, val payload: String, val state: String,
    val receipt: String?, val createdAt: Long, val staffId: Long?,
)

sealed interface ProposalActionResult {
    data class Held(val id: String) : ProposalActionResult
    data class Applied(val receipt: String) : ProposalActionResult
    data class AlreadyApplied(val receipt: String) : ProposalActionResult
    data class Rejected(val reason: String) : ProposalActionResult
    data class Conflict(val reason: String) : ProposalActionResult
    data object Stale : ProposalActionResult
    data object Dismissed : ProposalActionResult
}

/** The model can only hold a proposal. Each effect is checked again in the staff transaction. */
class StudioProposalStore(private val repository: StudioRepository) {
    private val db get() = repository.database

    fun list(limit: Int = 100): List<StudioProposal> {
        require(limit in 1..500)
        return db.rawQuery("""SELECT id,event_id,kind,contact_id,thread_id,source_revision,
            payload,state,receipt,created_at,staff_id FROM action_proposals
            ORDER BY CASE WHEN state='HELD' THEN 0 ELSE 1 END, created_at DESC,id DESC LIMIT ?""", arrayOf(limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(StudioProposal(
                    c.getString(0), c.getString(1), ProposalKind.valueOf(c.getString(2)),
                    c.getString(3), c.getString(4), c.getLong(5), c.getString(6),
                    c.getString(7), if (c.isNull(8)) null else c.getString(8), c.getLong(9),
                    if (c.isNull(10)) null else c.getLong(10),
                ))
            }
        }
    }

    fun propose(scope: DraftRequest, request: ScopedProposalRequest): ProposalActionResult {
        val checked = ToolProposals(scope).parse(request)
        if (checked is ProposalValidation.Rejected) return ProposalActionResult.Rejected(checked.reason)
        checked as ProposalValidation.Held
        return db.transaction {
            val source = db.studioSource(scope.eventId) ?: return@transaction ProposalActionResult.Stale
            if (source.threadId != scope.threadId || source.contactId != scope.contactId ||
                source.revision != scope.expectedRevision) return@transaction ProposalActionResult.Stale
            if (source.staffPaused) return@transaction ProposalActionResult.Rejected("Agent drafting is paused")
            existing(scope, checked)?.let { priorId ->
                val prior = checkNotNull(get(priorId))
                return@transaction when (prior.state) {
                    "APPROVED" -> ProposalActionResult.AlreadyApplied(requireNotNull(prior.receipt))
                    "DISMISSED" -> ProposalActionResult.Dismissed
                    "STALE" -> ProposalActionResult.Stale
                    else -> ProposalActionResult.Held(priorId)
                }
            }
            val id = UUID.randomUUID().toString()
            db.insertOrThrow("action_proposals", null, ContentValues().apply {
                put("id", id); put("event_id", scope.eventId); put("kind", checked.kind.name)
                put("contact_id", scope.contactId); put("thread_id", scope.threadId)
                put("source_revision", scope.expectedRevision); put("payload", checked.canonicalJson)
                put("created_at", System.currentTimeMillis())
            })
            ProposalActionResult.Held(id)
        }
    }

    private fun existing(scope: DraftRequest, checked: ProposalValidation.Held): String? =
        db.rawQuery("""SELECT id FROM action_proposals WHERE event_id=? AND kind=?
            AND source_revision=? AND payload=?""",
            arrayOf(scope.eventId, checked.kind.name, scope.expectedRevision.toString(), checked.canonicalJson),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun dismiss(id: String): ProposalActionResult = db.transaction {
        val item = get(id) ?: return@transaction ProposalActionResult.Rejected("Proposal not found")
        if (item.state == "APPROVED") return@transaction ProposalActionResult.AlreadyApplied(requireNotNull(item.receipt))
        if (item.state == "DISMISSED") return@transaction ProposalActionResult.Dismissed
        db.execSQL("UPDATE action_proposals SET state='DISMISSED' WHERE id=? AND state IN ('HELD','STALE')", arrayOf(id))
        ProposalActionResult.Dismissed
    }

    private fun get(id: String): StudioProposal? = db.rawQuery("""SELECT id,event_id,kind,contact_id,
        thread_id,source_revision,payload,state,receipt,created_at,staff_id
        FROM action_proposals WHERE id=?""", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else StudioProposal(
            c.getString(0), c.getString(1), ProposalKind.valueOf(c.getString(2)),
            c.getString(3), c.getString(4), c.getLong(5), c.getString(6), c.getString(7),
            if (c.isNull(8)) null else c.getString(8), c.getLong(9),
            if (c.isNull(10)) null else c.getLong(10),
        )
    }

    /** A staff tap supplies staffId; no model path can call this function. */
    fun confirm(id: String, staffId: Long): ProposalActionResult = db.transaction {
        val item = get(id) ?: return@transaction ProposalActionResult.Rejected("Proposal not found")
        if (item.state == "APPROVED") return@transaction ProposalActionResult.AlreadyApplied(requireNotNull(item.receipt))
        if (item.state == "DISMISSED") return@transaction ProposalActionResult.Dismissed
        if (item.state == "STALE") return@transaction ProposalActionResult.Stale
        if (staffId <= 0 || !db.rawQuery("SELECT 1 FROM studio_staff WHERE id=?",
                arrayOf(staffId.toString())).use { it.moveToFirst() })
            return@transaction ProposalActionResult.Rejected("Choose a current staff member")
        val source = db.studioSource(item.eventId)
        val contact = db.studioContact(item.contactId)
        if (source == null || contact == null || contact.paused ||
            source.contactId != item.contactId || source.threadId != item.threadId ||
            source.revision != item.sourceRevision) {
            db.execSQL("UPDATE action_proposals SET state='STALE' WHERE id=?", arrayOf(id))
            return@transaction ProposalActionResult.Stale
        }
        val scope = DraftRequest(item.eventId, item.threadId, item.contactId, item.sourceRevision)
        val name = when (item.kind) {
            ProposalKind.APPOINTMENT -> "propose_appointment"
            ProposalKind.MEMORY_CORRECTION -> "propose_memory_correction"
            ProposalKind.DRAFT_REPLY -> "draft_reply"
        }
        val checked = ToolProposals(scope).parse(
            ScopedProposalRequest(name, 1, item.contactId, item.threadId, item.payload),
        ) as? ProposalValidation.Held
            ?: return@transaction ProposalActionResult.Rejected("Stored proposal is invalid")
        if (checked.kind != item.kind || checked.canonicalJson != item.payload)
            return@transaction ProposalActionResult.Rejected("Stored proposal changed")
        val applied = when (val payload = checked.payload) {
            is ProposalPayload.Appointment -> applyAppointment(item, payload)
            is ProposalPayload.MemoryCorrection -> {
                db.saveStudioContactEdit(item.contactId, payload.body, contact.paused, "Staff-approved agent suggestion")
                ProposalActionResult.Applied("memory:${item.contactId}:${checkNotNull(db.studioContact(item.contactId)).revision}")
            }
            is ProposalPayload.DraftReply -> applyDraftReply(item, payload, source.recipient)
        }
        if (applied !is ProposalActionResult.Applied) return@transaction applied
        db.execSQL("""UPDATE action_proposals SET state='APPROVED',receipt=?,staff_id=?,
            confirmed_at=? WHERE id=? AND state='HELD'""",
            arrayOf<Any>(applied.receipt, staffId, System.currentTimeMillis(), id))
        applied
    }

    private fun applyAppointment(item: StudioProposal, payload: ProposalPayload.Appointment): ProposalActionResult {
        if (!db.rawQuery("SELECT 1 FROM studio_staff WHERE id=?", arrayOf(payload.staffId.toString())).use { it.moveToFirst() })
            return ProposalActionResult.Conflict("Artist is no longer available")
        val calendar = StudioCalendarStore(repository, reminders = { _, _, _ -> })
        if (payload.zoneId != calendar.studioZone().id)
            return ProposalActionResult.Conflict("Studio time zone changed; propose a current time")
        val proposal = CalendarProposal(
            payload.title, "appointment", payload.staffId, payload.startsAt, payload.endsAt,
            payload.zoneId, payload.room, item.contactId,
        )
        val valid = try { proposal.validate(); true } catch (_: IllegalArgumentException) { false }
        if (!valid) return ProposalActionResult.Rejected("Appointment time or zone is invalid")
        return when (val result = calendar.schedule(proposal)) {
            is ScheduleResult.Saved -> ProposalActionResult.Applied("calendar:${result.id}")
            is ScheduleResult.Conflict -> ProposalActionResult.Conflict(
                if (result.sharedRoom) "Room is booked" else "Artist is booked",
            )
            is ScheduleResult.Unavailable -> ProposalActionResult.Conflict(result.reason)
        }
    }

    private fun applyDraftReply(item: StudioProposal, payload: ProposalPayload.DraftReply, recipient: String): ProposalActionResult {
        if (db.rawQuery("""SELECT 1 FROM send_claims s JOIN held_drafts d ON d.draft_id=s.draft_id
            WHERE d.event_id=? LIMIT 1""", arrayOf(item.eventId)).use { it.moveToFirst() })
            return ProposalActionResult.Conflict("A reply has already been sent or claimed")
        if (db.rawQuery("SELECT 1 FROM held_drafts WHERE event_id=? AND kind='PROPOSAL_REPLY'",
                arrayOf(item.eventId)).use { it.moveToFirst() })
            return ProposalActionResult.Conflict("A reply proposal was already reviewed")
        db.execSQL(
            "UPDATE held_drafts SET state='DISMISSED', staff_edit_body=NULL WHERE event_id=? AND state='HELD'",
            arrayOf(item.eventId),
        )
        val draftId = UUID.randomUUID().toString()
        db.insertOrThrow("held_drafts", null, ContentValues().apply {
            put("draft_id", draftId); put("event_id", item.eventId); put("kind", "PROPOSAL_REPLY")
            put("thread_id", item.threadId); put("contact_id", item.contactId); put("recipient", recipient)
            put("source_revision", item.sourceRevision); put("body", payload.body)
            put("created_at", System.currentTimeMillis())
        })
        db.resolveStaffAttentionForEvent(item.eventId, item.sourceRevision)
        return ProposalActionResult.Applied("held-draft:$draftId")
    }
}
