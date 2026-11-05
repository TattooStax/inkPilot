package org.fossify.messages.inkpilot.ui

import android.app.AlertDialog
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import inkpilot.agent.ProposalKind
import org.fossify.messages.inkpilot.data.ProposalActionResult
import org.fossify.messages.inkpilot.data.StudioProposal
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.studio.StudioCalendarStore
import org.fossify.messages.inkpilot.studio.StudioStaff
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The only entry point to confirm a proposal; staff identity is chosen at the tap. */
internal class ProposalPanel(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
) {
    fun view(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Action proposals", 24f, true))
        body.addView(StudioUi.text(activity, "Review every detail. Calendar, client memory, and reply drafts change only after staff approval. Replies still need a separate SMS review.", 14f))
        body.addView(StudioUi.button(activity, "Refresh", refresh))
        val list = StudioUi.column(activity, 0)
        body.addView(list)
        list.addView(StudioUi.text(activity, "Loading proposals…"))
        work.load({ Triple(repository.proposals(), StudioCalendarStore(repository).staff(), repository.contacts()) }) { (proposals, staff, contacts) ->
            list.removeAllViews()
            if (proposals.isEmpty()) list.addView(StudioUi.text(activity, "No action proposals yet."))
            proposals.forEach { proposal ->
                val client = contacts.firstOrNull { it.id == proposal.contactId }?.displayName ?: "Client"
                list.addView(StudioUi.text(activity, "${label(proposal.kind)} · $client", 18f, true))
                list.addView(StudioUi.text(activity, details(proposal, staff), 14f))
                list.addView(StudioUi.text(activity, proposal.state, 13f))
                if (proposal.state == "HELD") {
                    list.addView(StudioUi.button(activity, "Review and approve") {
                        if (staff.isEmpty()) {
                            StudioUi.error(activity, "Add a staff member in Calendar first.")
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("Approve ${label(proposal.kind)} as which staff member?")
                                .setItems(staff.map { it.name }.toTypedArray()) { _, index ->
                                    work.load({ repository.confirmProposal(proposal.id, staff[index].id) }) { result ->
                                        StudioUi.error(activity, outcome(result))
                                        refresh()
                                    }
                                }.setNegativeButton("Cancel", null).show()
                        }
                    })
                    list.addView(StudioUi.button(activity, "Dismiss") {
                        work.load({ repository.dismissProposal(proposal.id) }) { refresh() }
                    })
                }
                list.addView(StudioUi.rule(activity))
            }
        }
        return StudioUi.scroll(activity, body)
    }

    private fun details(proposal: StudioProposal, staff: List<StudioStaff>): String = try {
        val payload = JSONObject(proposal.payload)
        when (proposal.kind) {
            ProposalKind.APPOINTMENT -> {
                val artist = staff.firstOrNull { it.id == payload.getLong("staffId") }?.name ?: "Artist unavailable"
                val zone = ZoneId.of(payload.getString("zoneId"))
                val starts = Instant.ofEpochMilli(payload.getLong("startsAt")).atZone(zone)
                val ends = Instant.ofEpochMilli(payload.getLong("endsAt")).atZone(zone)
                val date = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")
                val time = DateTimeFormatter.ofPattern("h:mm a")
                "${payload.getString("title")}\n$artist · ${date.format(starts)} to ${time.format(ends)} · ${zone.id}\nRoom: ${payload.getString("room").ifBlank { "Unassigned" }}"
            }
            ProposalKind.MEMORY_CORRECTION -> "Replace this client's memory with:\n${payload.getString("body")}"
            ProposalKind.DRAFT_REPLY -> "Hold a reply for separate SMS review:\n${payload.getString("body")}"
        }
    } catch (_: Exception) { "Proposal details unavailable. Dismiss and review the conversation." }

    private fun label(kind: ProposalKind) = when (kind) {
        ProposalKind.APPOINTMENT -> "Appointment"
        ProposalKind.MEMORY_CORRECTION -> "Client memory correction"
        ProposalKind.DRAFT_REPLY -> "Reply draft"
    }

    private fun outcome(result: ProposalActionResult): String = when (result) {
        is ProposalActionResult.Applied -> "Approved: ${result.receipt}"
        is ProposalActionResult.AlreadyApplied -> "Already approved: ${result.receipt}"
        is ProposalActionResult.Conflict -> result.reason
        is ProposalActionResult.Rejected -> result.reason
        ProposalActionResult.Stale -> "The source changed. Review the current conversation."
        ProposalActionResult.Dismissed -> "Proposal dismissed."
        is ProposalActionResult.Held -> "Still awaiting review."
    }
}
