package org.fossify.messages.inkpilot.studio

import android.content.ContentValues
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.transaction

internal class StudioCalendarStore(private val repository: StudioRepository) {
    private val db get() = repository.database

    fun staff(): List<StudioStaff> = db.rawQuery("SELECT id,name FROM studio_staff ORDER BY name COLLATE NOCASE", null).use {
        buildList { while (it.moveToNext()) add(StudioStaff(it.getLong(0), it.getString(1))) }
    }

    fun addStaff(name: String): Long {
        val value = name.trim()
        require(value.isNotEmpty() && value.length <= 80) { "Enter a staff name of 80 characters or fewer." }
        return db.insertOrThrow("studio_staff", null, ContentValues().apply { put("name", value) })
    }

    fun entries(startsAt: Long, endsAt: Long): List<CalendarEntry> = db.rawQuery(
        """SELECT e.id,e.title,e.kind,e.staff_id,s.name,e.starts_at,e.ends_at,e.zone_id,e.room,e.status
            FROM studio_calendar e JOIN studio_staff s ON s.id=e.staff_id
            WHERE e.starts_at<? AND e.ends_at>? ORDER BY e.starts_at,e.id""",
        arrayOf(endsAt.toString(), startsAt.toString()),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(CalendarEntry(
            cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
            cursor.getString(4), cursor.getLong(5), cursor.getLong(6), cursor.getString(7),
            cursor.getString(8), cursor.getString(9),
        ))
    } }

    fun schedule(proposal: CalendarProposal): ScheduleResult = db.transaction {
        proposal.validate()
        db.rawQuery(
            """SELECT title,staff_id FROM studio_calendar WHERE status='scheduled'
                AND starts_at<? AND ends_at>? AND (staff_id=? OR (?<>'' AND room=? COLLATE NOCASE)) LIMIT 1""",
            arrayOf(proposal.endsAt.toString(), proposal.startsAt.toString(), proposal.staffId.toString(),
                proposal.room.trim(), proposal.room.trim()),
        ).use { cursor ->
            if (cursor.moveToFirst()) return@transaction ScheduleResult.Conflict(
                cursor.getString(0), cursor.getLong(1) != proposal.staffId,
            )
        }
        val values = ContentValues().apply {
            put("title", proposal.title.trim()); put("kind", proposal.kind); put("staff_id", proposal.staffId)
            put("starts_at", proposal.startsAt); put("ends_at", proposal.endsAt)
            put("zone_id", proposal.zoneId); put("room", proposal.room.trim()); put("status", "scheduled")
        }
        ScheduleResult.Saved(db.insertOrThrow("studio_calendar", null, values))
    }

    fun setStatus(id: Long, status: String) {
        require(status in setOf("completed", "cancelled"))
        check(db.update("studio_calendar", ContentValues().apply { put("status", status) },
            "id=? AND status='scheduled'", arrayOf(id.toString())) == 1) { "This appointment or task has already changed." }
    }
}
