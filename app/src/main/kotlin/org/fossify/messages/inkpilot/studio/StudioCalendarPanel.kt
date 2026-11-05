package org.fossify.messages.inkpilot.studio

import android.app.DatePickerDialog
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class StudioCalendarPanel(private val host: StudioPanelHost, private val store: StudioCalendarStore) {
    private var zone = ZoneId.systemDefault()
    private var day = LocalDate.now(zone)
    private var content = host.column()
    private var renderGeneration = 0L
    private var zoneLoadGeneration = 0L
    private var zoneReady = false
    private var zoneSaving = false
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

    fun view(): View {
        content = host.column()
        zoneReady = false
        render()
        loadZone()
        host.load({ store.reconcileReminders() }) {}
        return host.body(content)
    }

    private fun loadZone() {
        if (zoneSaving) return
        val generation = ++zoneLoadGeneration
        host.load({ store.studioZone() }) { current ->
            if (generation != zoneLoadGeneration) return@load
            if (current != zone) day = LocalDate.now(current)
            zone = current
            zoneReady = true
            render()
        }
    }

    private fun render() {
        val generation = ++renderGeneration
        content.removeAllViews()
        content.addView(host.text("Calendar", 22f))
        calendarReminderNotice(host, CalendarReminderGate.canPost(host.activity,
            host.activity.getSystemService(android.app.NotificationManager::class.java)))?.let(content::addView)
        if (!zoneReady) {
            content.addView(host.text(if (zoneSaving) "Saving studio time zone…" else "Loading studio time zone…"))
            if (!zoneSaving) content.addView(host.button("Retry calendar") { loadZone() })
            return
        }
        val dates = LinearLayout(host.activity)
        dates.addView(host.button("‹") { day = day.minusDays(1); render() })
        dates.addView(host.button(day.format(dateFormat)) {
            DatePickerDialog(host.activity, { _, year, month, date ->
                day = LocalDate.of(year, month + 1, date); render()
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dates.addView(host.button("›") { day = day.plusDays(1); render() })
        content.addView(dates)
        content.addView(host.button("Add appointment or task") {
            host.load({ store.staff() to store.contacts() }) { (staff, contacts) ->
                if (staff.isEmpty()) addStaff() else CalendarEditor(host, store, day, staff, contacts, ::render, zone).show()
            }
        })
        content.addView(host.button("Staff hours and time off") { chooseStaffForAvailability() })
        content.addView(host.button("Add staff member") { addStaff() })
        content.addView(host.button("Studio time zone · ${zone.id}") { editStudioZone() })
        val entries = host.column()
        content.addView(entries)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        host.load({ store.entries(start, end) }) { rows ->
            if (generation != renderGeneration) return@load
            if (rows.isEmpty()) entries.addView(host.text("Nothing scheduled for this day."))
            rows.forEach { entry ->
                val from = Instant.ofEpochMilli(entry.startsAt).atZone(zone).format(timeFormat)
                val until = Instant.ofEpochMilli(entry.endsAt).atZone(zone).format(timeFormat)
                val status = if (entry.status == "scheduled") "" else " · ${entry.status}"
                val room = if (entry.room.isBlank()) "" else " · ${entry.room}"
                entries.addView(host.button("$from–$until · ${entry.title}\n${entry.staffName}$room$status") { details(entry) })
            }
        }
    }

    private fun editStudioZone() {
        val field = host.field("IANA time zone", zone.id)
        val dialog = AlertDialog.Builder(host.activity).setTitle("Studio time zone")
            .setView(field).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val candidate = runCatching { ZoneId.of(field.text.toString().trim()).id }.getOrElse {
                host.onError("Enter a valid IANA time zone."); return@setOnClickListener
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            ++zoneLoadGeneration
            zoneReady = false
            zoneSaving = true
            render()
            host.load({ runCatching { store.setStudioZone(candidate); store.studioZone() } }) { result ->
                zoneSaving = false
                result.onSuccess { selected ->
                    zone = selected; day = LocalDate.now(zone); zoneReady = true
                    dialog.dismiss(); render()
                }.onFailure { error ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    loadZone()
                    host.onError(error.message ?: "The studio time zone could not be saved.")
                }
            }
        } }
        dialog.show()
    }

    private fun addStaff() {
        val name = host.field("Staff name")
        val dialog = AlertDialog.Builder(host.activity).setTitle("Add staff member").setView(name)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                host.load({ store.addStaff(name.text.toString()) }) { dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }

    private fun details(entry: CalendarEntry) { detailsDialog(entry).show() }

    internal fun detailsDialog(entry: CalendarEntry): AlertDialog {
        val message = "${entry.staffName}\n${entry.kind.replaceFirstChar { it.uppercase() }}" +
            (if (entry.room.isBlank()) "" else "\n${entry.room}") +
            (if (entry.reminderAt == null) "" else "\nReminder set") +
            "\n${entry.status.replaceFirstChar { it.uppercase() }}"
        if (entry.status != "scheduled") {
            return AlertDialog.Builder(host.activity).setTitle(entry.title).setMessage(message)
                .setPositiveButton("Close", null).create()
        }
        val heading = host.column().apply {
            setPadding(host.dp(18), host.dp(8), host.dp(18), 0)
            addView(host.text(entry.title, 20f)); addView(host.text(message, 14f))
        }
        return AlertDialog.Builder(host.activity).setCustomTitle(heading)
            .setItems(arrayOf("Edit", "Mark completed", "Cancel entry")) { _, which ->
                when (which) {
                    0 -> host.load({ store.staff() to store.contacts() }) { (staff, contacts) ->
                        CalendarEditor(host, store, day, staff, contacts, ::render, zone, entry).show()
                    }
                    1 -> host.load({ store.setStatus(entry.id, "completed") }) { render() }
                    2 -> AlertDialog.Builder(host.activity).setTitle("Cancel ${entry.title}?")
                        .setPositiveButton("Cancel entry") { _, _ ->
                            host.load({ store.setStatus(entry.id, "cancelled") }) { render() }
                        }.setNegativeButton("Keep", null).show()
                }
            }.setNegativeButton("Close", null).create()
    }

    private fun chooseStaffForAvailability() {
        host.load({ store.staff() }) { staff ->
            if (staff.isEmpty()) { addStaff(); return@load }
            AlertDialog.Builder(host.activity).setTitle("Staff member")
                .setItems(staff.map { it.name }.toTypedArray()) { _, index -> availability(staff[index]) }.show()
        }
    }

    private fun availability(staff: StudioStaff) {
        host.load({ store.hours(staff.id) to store.timeOff(staff.id) }) { (hours, off) ->
            availabilityDialog(staff, hours, off).show()
        }
    }

    internal fun availabilityDialog(staff: StudioStaff, hours: List<StaffHours>, off: List<TimeOff>): AlertDialog {
            val rows = buildList {
                add("Set weekly hours")
                add("Add time off")
                hours.forEach { add("Remove ${weekday(it.weekday)} ${clock(it.opensMinute)}–${clock(it.closesMinute)}") }
                off.forEach { add("Remove time off: ${it.label} · ${Instant.ofEpochMilli(it.startsAt).atZone(zone).toLocalDate()}") }
            }
            val explanation = if (hours.isEmpty())
                "Hours unset: bookings are allowed unless time off or another entry conflicts."
                else "Unlisted weekdays are unavailable."
            val heading = host.column().apply {
                setPadding(host.dp(18), host.dp(8), host.dp(18), 0)
                addView(host.text("${staff.name} · availability", 20f))
                addView(host.text(explanation, 14f))
            }
            var childEditor: (() -> Unit)? = null
            return AlertDialog.Builder(host.activity).setCustomTitle(heading)
                .setItems(rows.toTypedArray()) { _, index ->
                    when {
                        // The list dialog queues its own dismissal after this callback.
                        // Show the editor only once that dismissal has completed.
                        index == 0 -> childEditor = { setHours(staff) }
                        index == 1 -> childEditor = { addTimeOff(staff) }
                        index - 2 < hours.size -> host.load({
                            store.clearWeeklyHours(staff.id, hours[index - 2].weekday)
                        }) { availability(staff) }
                        else -> host.load({ store.removeTimeOff(off[index - 2 - hours.size].id) }) { availability(staff) }
                    }
                }.setNegativeButton("Close", null).create().apply {
                    setOnDismissListener {
                        val pending = childEditor
                        childEditor = null
                        if (pending != null) host.onUi(pending)
                    }
                }
    }

    private fun setHours(staff: StudioStaff) {
        val fields = host.column()
        val weekday = host.field("Weekday: 1=Mon through 7=Sun")
        val opens = host.field("Open HH:mm (24-hour)", "09:00")
        val closes = host.field("Close HH:mm (24-hour)", "17:00")
        fields.addView(weekday); fields.addView(opens); fields.addView(closes)
        val dialog = AlertDialog.Builder(host.activity).setTitle("${staff.name} · weekly hours")
            .setView(host.body(fields)).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val dayNumber = weekday.text.toString().toIntOrNull()
            val from = minutes(opens.text.toString()); val until = minutes(closes.text.toString())
            if (dayNumber !in 1..7 || from == null || until == null || until <= from) {
                host.onError("Use weekday 1–7 and valid same-day opening and closing times."); return@setOnClickListener
            }
            host.load({ store.setWeeklyHours(staff.id, dayNumber!!, from, until) }) {
                dialog.dismiss(); availability(staff); render()
            }
        } }
        dialog.show()
    }

    private fun addTimeOff(staff: StudioStaff) {
        val fields = host.column()
        val from = host.field("First day YYYY-MM-DD")
        val until = host.field("Last day YYYY-MM-DD")
        val label = host.field("Reason (optional)")
        fields.addView(from); fields.addView(until); fields.addView(label)
        val dialog = AlertDialog.Builder(host.activity).setTitle("${staff.name} · time off")
            .setView(host.body(fields)).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val range = runCatching {
                val start = LocalDate.parse(from.text.toString()).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = LocalDate.parse(until.text.toString()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                start to end
            }.getOrNull()
            if (range == null || range.second <= range.first) {
                host.onError("Enter a valid first and last day."); return@setOnClickListener
            }
            host.load({ store.addTimeOff(staff.id, range.first, range.second, label.text.toString()) }) {
                dialog.dismiss(); availability(staff); render()
            }
        } }
        dialog.show()
    }

    private fun weekday(value: Int) = listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[value - 1]
    private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
    private fun minutes(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }
}

/** Keep the disabled state visible while staff are using the calendar. */
internal fun calendarReminderNotice(host: StudioPanelHost, available: Boolean): View? =
    if (available) null else host.text("Reminders paused. Enable inkPilot notifications in Android settings. " +
        "Saved reminders will retry.")
