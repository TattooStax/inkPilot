package org.fossify.messages.inkpilot.studio

import java.time.Instant
import java.time.ZoneId

internal data class StudioAlbum(val id: Long, val name: String, val artist: String)
internal data class StudioImage(val id: Long, val filename: String, val displayName: String)
internal data class StudioStaff(val id: Long, val name: String)
internal data class CalendarEntry(
    val id: Long, val title: String, val kind: String, val staffId: Long,
    val staffName: String, val startsAt: Long, val endsAt: Long,
    val zoneId: String, val room: String, val status: String,
    val contactId: String? = null, val projectId: String? = null,
    val reminderAt: Long? = null, val revision: Long = 0,
)
internal data class CalendarProposal(
    val title: String, val kind: String, val staffId: Long,
    val startsAt: Long, val endsAt: Long, val zoneId: String, val room: String,
    val contactId: String? = null, val projectId: String? = null,
    val reminderAt: Long? = null,
) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 160) { "Enter a title of 160 characters or fewer." }
        require(kind in setOf("appointment", "task")) { "Choose an appointment or task." }
        require(staffId > 0 && endsAt > startsAt && endsAt - startsAt in 60_000..86_400_000) {
            "Choose a staff member and a duration between one minute and 24 hours."
        }
        require(startsAt % 60_000 == 0L && endsAt % 60_000 == 0L) { "Use whole-minute times." }
        require(room.length <= 80) { "Keep the room name under 80 characters." }
        require(projectId == null || (projectId.isNotBlank() && projectId.length <= 80)) { "Keep the project ID under 80 characters." }
        require(reminderAt == null || reminderAt in 0 until startsAt) { "Set a reminder before the entry." }
        val zone = ZoneId.of(zoneId)
        listOf(startsAt, endsAt - 1).forEach { epoch ->
            val local = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDateTime()
            require(zone.rules.getValidOffsets(local).size == 1) { "Choose a time outside a daylight-saving change." }
        }
        val transition = zone.rules.nextTransition(Instant.ofEpochMilli(startsAt - 1))
        require(transition == null || transition.instant.toEpochMilli() >= endsAt) {
            "Choose a time outside a daylight-saving change."
        }
    }
}

internal sealed interface ScheduleResult {
    data class Saved(val id: Long, val revision: Long = 0) : ScheduleResult
    data class Conflict(val title: String, val sharedRoom: Boolean) : ScheduleResult
    data class Unavailable(val reason: String) : ScheduleResult
}

/** Read-only proposal over the same canonical store used by staff. */
internal class CalendarAvailability(private val store: StudioCalendarStore) {
    fun propose(contactId: String?, staffId: Long, room: String, start: Long, end: Long, zone: String): Result =
        store.availability(contactId, staffId, room, start, end, zone)

    sealed interface Result
    data object Available : Result
    data class Conflict(val reason: String) : Result
}
