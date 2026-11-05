package org.fossify.messages.inkpilot.studio

import android.content.ContentValues
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.transaction
import java.time.ZoneId

internal data class StaffHours(val weekday: Int, val opensMinute: Int, val closesMinute: Int)
internal data class TimeOff(val id: Long, val staffId: Long, val startsAt: Long, val endsAt: Long, val label: String)

/** The canonical studio calendar, used by staff screens and future checked agent proposals. */
internal class StudioCalendarStore(
    private val repository: StudioRepository,
    private val reminders: (Long, Long, Long?) -> Unit = { id, revision, due ->
        CalendarReminderScheduler(repository.applicationContext).schedule(id, revision, due)
    },
) {
    private val db get() = repository.database

    fun staff(): List<StudioStaff> = db.rawQuery("SELECT id,name FROM studio_staff ORDER BY name COLLATE NOCASE", null).use {
        buildList { while (it.moveToNext()) add(StudioStaff(it.getLong(0), it.getString(1))) }
    }

    fun contacts() = repository.contacts()

    fun studioZone(): ZoneId = db.rawQuery("SELECT zone_id FROM studio_calendar_settings WHERE id=1", null).use {
        check(it.moveToFirst())
        ZoneId.of(it.getString(0))
    }

    fun setStudioZone(value: String) {
        val zone = ZoneId.of(value.trim())
        db.transaction {
            db.rawQuery("""SELECT staff_id,starts_at,ends_at FROM studio_calendar
                WHERE status='scheduled'""", null).use { rows ->
                while (rows.moveToNext()) {
                    CalendarProposal("Existing", "task", rows.getLong(0), rows.getLong(1),
                        rows.getLong(2), zone.id, "").validateTimeZone(zone)
                    val rules = hours(rows.getLong(0)).associateBy { it.weekday }
                    if (rules.isNotEmpty()) check(CalendarWallHours.fits(rows.getLong(1), rows.getLong(2), zone, rules)) {
                        "Move or cancel booked entries before changing the studio time zone."
                    }
                }
            }
            db.execSQL("UPDATE studio_calendar_settings SET zone_id=? WHERE id=1", arrayOf(zone.id))
        }
    }

    fun addStaff(name: String): Long {
        val value = name.trim()
        require(value.isNotEmpty() && value.length <= 80) { "Enter a staff name of 80 characters or fewer." }
        return db.insertOrThrow("studio_staff", null, ContentValues().apply { put("name", value) })
    }

    fun entries(startsAt: Long, endsAt: Long): List<CalendarEntry> = db.rawQuery(
        """SELECT e.id,e.title,e.kind,e.staff_id,s.name,e.starts_at,e.ends_at,e.zone_id,e.room,e.status,
            e.contact_id,e.project_id,e.reminder_at,e.revision
            FROM studio_calendar e JOIN studio_staff s ON s.id=e.staff_id
            WHERE e.starts_at<? AND e.ends_at>? ORDER BY e.starts_at,e.id""",
        arrayOf(endsAt.toString(), startsAt.toString()),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(CalendarEntry(
            cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
            cursor.getString(4), cursor.getLong(5), cursor.getLong(6), cursor.getString(7),
            cursor.getString(8), cursor.getString(9),
            if (cursor.isNull(10)) null else cursor.getString(10),
            if (cursor.isNull(11)) null else cursor.getString(11),
            if (cursor.isNull(12)) null else cursor.getLong(12), cursor.getLong(13),
        ))
    } }

    fun hours(staffId: Long): List<StaffHours> = db.rawQuery(
        "SELECT weekday,opens_minute,closes_minute FROM studio_staff_hours WHERE staff_id=? ORDER BY weekday",
        arrayOf(staffId.toString()),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(StaffHours(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
    } }

    fun setWeeklyHours(staffId: Long, weekday: Int, opensMinute: Int, closesMinute: Int) {
        require(weekday in 1..7 && opensMinute in 0..1439 && closesMinute in (opensMinute + 1)..1440)
        db.transaction {
            val proposed = hours(staffId).associateBy { it.weekday }.toMutableMap()
            proposed[weekday] = StaffHours(weekday, opensMinute, closesMinute)
            db.rawQuery("""SELECT starts_at,ends_at,zone_id FROM studio_calendar
                WHERE staff_id=? AND status='scheduled'""", arrayOf(staffId.toString())).use { rows ->
                while (rows.moveToNext()) {
                    check(CalendarWallHours.fits(rows.getLong(0), rows.getLong(1), studioZone(), proposed)) {
                        "Move or cancel booked entries before changing hours."
                    }
                }
            }
            db.execSQL("""INSERT OR REPLACE INTO studio_staff_hours(staff_id,weekday,opens_minute,closes_minute)
                VALUES(?,?,?,?)""", arrayOf<Any>(staffId, weekday, opensMinute, closesMinute))
        }
    }

    fun clearWeeklyHours(staffId: Long, weekday: Int) {
        require(weekday in 1..7)
        db.transaction {
            val remaining = hours(staffId).filterNot { it.weekday == weekday }.associateBy { it.weekday }
            if (remaining.isNotEmpty()) {
                db.rawQuery("""SELECT starts_at,ends_at,zone_id FROM studio_calendar
                    WHERE staff_id=? AND status='scheduled'""", arrayOf(staffId.toString())).use { rows ->
                    while (rows.moveToNext()) {
                        check(CalendarWallHours.fits(rows.getLong(0), rows.getLong(1), studioZone(), remaining)) {
                            "Move or cancel booked entries before changing hours."
                        }
                    }
                }
            }
            db.delete("studio_staff_hours", "staff_id=? AND weekday=?",
                arrayOf(staffId.toString(), weekday.toString()))
        }
    }

    fun timeOff(staffId: Long): List<TimeOff> = db.rawQuery(
        "SELECT id,staff_id,starts_at,ends_at,label FROM studio_time_off WHERE staff_id=? ORDER BY starts_at",
        arrayOf(staffId.toString()),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(TimeOff(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2),
            cursor.getLong(3), cursor.getString(4)))
    } }

    fun addTimeOff(staffId: Long, startsAt: Long, endsAt: Long, label: String): Long {
        require(staffId > 0 && startsAt >= 0 && endsAt > startsAt && label.length <= 80)
        return db.transaction {
            db.rawQuery("""SELECT 1 FROM studio_calendar WHERE staff_id=? AND status='scheduled'
                AND starts_at<? AND ends_at>? LIMIT 1""",
                arrayOf(staffId.toString(), endsAt.toString(), startsAt.toString())).use {
                check(!it.moveToFirst()) { "Move or cancel booked entries before recording time off." }
            }
            db.insertOrThrow("studio_time_off", null, ContentValues().apply {
                put("staff_id", staffId); put("starts_at", startsAt); put("ends_at", endsAt); put("label", label.trim())
            })
        }
    }

    fun removeTimeOff(id: Long) { db.delete("studio_time_off", "id=?", arrayOf(id.toString())) }

    fun availability(contactId: String?, staffId: Long, room: String, start: Long, end: Long, zone: String): CalendarAvailability.Result {
        val proposal = CalendarProposal("Availability check", "appointment", staffId, start, end, zone, room, contactId)
        proposal.validate()
        if (contactId != null && repository.contact(contactId) == null) {
            return CalendarAvailability.Conflict("Client record unavailable.")
        }
        db.rawQuery("SELECT 1 FROM studio_staff WHERE id=?", arrayOf(staffId.toString())).use {
            if (!it.moveToFirst()) return CalendarAvailability.Conflict("Staff unavailable.")
        }
        return when (val result = check(proposal, null)) {
            null -> CalendarAvailability.Available
            is ScheduleResult.Conflict -> CalendarAvailability.Conflict(
                if (result.sharedRoom) "Room unavailable." else "Staff unavailable.",
            )
            is ScheduleResult.Unavailable -> CalendarAvailability.Conflict("Staff unavailable.")
            else -> error("Only conflict results are returned by availability")
        }
    }

    fun schedule(proposal: CalendarProposal): ScheduleResult {
        val result = db.transaction {
            proposal.validate()
            check(proposal, null)?.let { return@transaction it }
            val id = db.insertOrThrow("studio_calendar", null, values(proposal))
            ScheduleResult.Saved(id)
        }
        if (result is ScheduleResult.Saved) reminders(result.id, 0, proposal.reminderAt)
        return result
    }

    fun edit(id: Long, proposal: CalendarProposal, expectedRevision: Long? = null): ScheduleResult {
        val result = db.transaction {
            proposal.validate()
            val current = db.rawQuery("SELECT revision,status FROM studio_calendar WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
            } ?: return@transaction ScheduleResult.Unavailable("This entry no longer exists.")
            if (current.second != "scheduled" || (expectedRevision != null && expectedRevision != current.first)) {
                return@transaction ScheduleResult.Unavailable("This entry has already changed. Reopen it before editing.")
            }
            check(proposal, id)?.let { return@transaction it }
            val values = values(proposal).apply { put("revision", current.first + 1) }
            check(db.update("studio_calendar", values, "id=? AND revision=? AND status='scheduled'",
                arrayOf(id.toString(), current.first.toString())) == 1)
            ScheduleResult.Saved(id, current.first + 1)
        }
        if (result is ScheduleResult.Saved) reminders(id, result.revision, proposal.reminderAt)
        return result
    }

    fun setStatus(id: Long, status: String) {
        require(status in setOf("completed", "cancelled"))
        val nextRevision = db.transaction {
            val current = requireNotNull(entryById(id)) { "This entry no longer exists." }
            check(current.status == "scheduled") { "This appointment or task has already changed." }
            check(db.update("studio_calendar", ContentValues().apply {
                put("status", status); putNull("reminder_at"); put("revision", current.revision + 1)
            }, "id=? AND status='scheduled' AND revision=?",
                arrayOf(id.toString(), current.revision.toString())) == 1) {
                "This appointment or task has already changed."
            }
            current.revision + 1
        }
        reminders(id, nextRevision, null)
    }

    fun pendingReminder(id: Long, revision: Long): CalendarEntry? =
        entryById(id)?.takeIf { it.status == "scheduled" && it.revision == revision && it.reminderAt != null }

    fun dueReminder(id: Long, revision: Long, now: Long): CalendarEntry? =
        pendingReminder(id, revision)?.takeIf { requireNotNull(it.reminderAt) <= now }

    fun markReminderDelivered(id: Long, revision: Long): Boolean =
        db.update("studio_calendar", ContentValues().apply { putNull("reminder_at") },
            "id=? AND revision=? AND status='scheduled' AND reminder_at IS NOT NULL",
            arrayOf(id.toString(), revision.toString())) == 1

    fun reconcileReminders() {
        db.rawQuery("""SELECT id,revision,reminder_at FROM studio_calendar
            WHERE status='scheduled' AND reminder_at IS NOT NULL""", null).use { c ->
            while (c.moveToNext()) reminders(c.getLong(0), c.getLong(1), c.getLong(2))
        }
    }

    private fun entryById(id: Long): CalendarEntry? = db.rawQuery(
        """SELECT e.id,e.title,e.kind,e.staff_id,s.name,e.starts_at,e.ends_at,e.zone_id,e.room,e.status,
            e.contact_id,e.project_id,e.reminder_at,e.revision
            FROM studio_calendar e JOIN studio_staff s ON s.id=e.staff_id WHERE e.id=?""", arrayOf(id.toString()),
    ).use { c ->
        if (!c.moveToFirst()) null else CalendarEntry(c.getLong(0), c.getString(1), c.getString(2),
            c.getLong(3), c.getString(4), c.getLong(5), c.getLong(6), c.getString(7), c.getString(8),
            c.getString(9), if (c.isNull(10)) null else c.getString(10),
            if (c.isNull(11)) null else c.getString(11), if (c.isNull(12)) null else c.getLong(12), c.getLong(13))
    }

    private fun values(p: CalendarProposal) = ContentValues().apply {
        put("title", p.title.trim()); put("kind", p.kind); put("staff_id", p.staffId)
        put("starts_at", p.startsAt); put("ends_at", p.endsAt)
        put("zone_id", p.zoneId); put("room", p.room.trim()); put("status", "scheduled")
        if (p.contactId == null) putNull("contact_id") else put("contact_id", p.contactId)
        if (p.projectId == null) putNull("project_id") else put("project_id", p.projectId)
        if (p.reminderAt == null) putNull("reminder_at") else put("reminder_at", p.reminderAt)
    }

    private fun check(p: CalendarProposal, exceptId: Long?): ScheduleResult? {
        p.validateTimeZone(studioZone())
        val args = arrayOf(p.endsAt.toString(), p.startsAt.toString(), p.staffId.toString(),
            p.room.trim(), p.room.trim(), (exceptId ?: -1).toString())
        db.rawQuery("""SELECT title,staff_id FROM studio_calendar WHERE status='scheduled'
            AND starts_at<? AND ends_at>? AND (staff_id=? OR (?<>'' AND room=? COLLATE NOCASE))
            AND id<>? LIMIT 1""", args).use { cursor ->
            if (cursor.moveToFirst()) return ScheduleResult.Conflict(cursor.getString(0), cursor.getLong(1) != p.staffId)
        }
        db.rawQuery("""SELECT label FROM studio_time_off WHERE staff_id=? AND starts_at<? AND ends_at>? LIMIT 1""",
            arrayOf(p.staffId.toString(), p.endsAt.toString(), p.startsAt.toString())).use {
            if (it.moveToFirst()) return ScheduleResult.Unavailable("Staff time off: ${it.getString(0)}")
        }
        val hours = hours(p.staffId).associateBy { it.weekday }
        if (hours.isNotEmpty() && !CalendarWallHours.fits(p.startsAt, p.endsAt, studioZone(), hours)) {
            return ScheduleResult.Unavailable("Outside this staff member's hours.")
        }
        return null
    }

}
