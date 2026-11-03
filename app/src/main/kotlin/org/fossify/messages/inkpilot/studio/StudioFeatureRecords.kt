package org.fossify.messages.inkpilot.studio

import java.time.ZoneId

internal data class StudioAlbum(val id: Long, val name: String, val artist: String)
internal data class StudioImage(val id: Long, val filename: String, val displayName: String)
internal data class StudioStaff(val id: Long, val name: String)
internal data class CalendarEntry(
    val id: Long, val title: String, val kind: String, val staffId: Long,
    val staffName: String, val startsAt: Long, val endsAt: Long,
    val zoneId: String, val room: String, val status: String,
)
internal data class CalendarProposal(
    val title: String, val kind: String, val staffId: Long,
    val startsAt: Long, val endsAt: Long, val zoneId: String, val room: String,
) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 160) { "Enter a title of 160 characters or fewer." }
        require(kind in setOf("appointment", "task")) { "Choose an appointment or task." }
        require(staffId > 0 && endsAt > startsAt && endsAt - startsAt <= 86_400_000) {
            "Choose a staff member and a duration between one minute and 24 hours."
        }
        require(room.length <= 80) { "Keep the room name under 80 characters." }
        ZoneId.of(zoneId)
    }
}

internal sealed interface ScheduleResult {
    data class Saved(val id: Long) : ScheduleResult
    data class Conflict(val title: String, val sharedRoom: Boolean) : ScheduleResult
}
