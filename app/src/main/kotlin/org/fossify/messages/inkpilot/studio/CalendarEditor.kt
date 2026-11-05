package org.fossify.messages.inkpilot.studio

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.fossify.messages.inkpilot.data.StudioContact

internal class CalendarEditor(
    private val host: StudioPanelHost,
    private val store: StudioCalendarStore,
    initialDay: LocalDate,
    private val staff: List<StudioStaff>,
    private val contacts: List<StudioContact>,
    private val onSaved: () -> Unit,
    private val zone: ZoneId,
    private val existing: CalendarEntry? = null,
) {
    private var day = existing?.let { Instant.ofEpochMilli(it.startsAt).atZone(zone).toLocalDate() } ?: initialDay
    private var time = existing?.let { Instant.ofEpochMilli(it.startsAt).atZone(zone).toLocalTime() } ?: LocalTime.NOON

    fun show() {
        val fields = host.column().apply { setPadding(host.dp(20), 0, host.dp(20), 0) }
        val title = host.field("Title", existing?.title.orEmpty())
        val artist = spinner(staff.map { it.name }).apply {
            setSelection(staff.indexOfFirst { it.id == existing?.staffId }.coerceAtLeast(0))
        }
        val kind = spinner(listOf("Appointment", "Task")).apply {
            setSelection(if (existing?.kind == "task") 1 else 0)
        }
        val clients = spinner(listOf("No client") + contacts.map { it.displayName }).apply {
            setSelection((contacts.indexOfFirst { it.id == existing?.contactId } + 1).coerceAtLeast(0))
        }
        val room = host.field("Room (optional)", existing?.room.orEmpty())
        val project = host.field("Project ID (optional)", existing?.projectId.orEmpty())
        val durationValue = existing?.let { ((it.endsAt - it.startsAt) / 60_000).toString() } ?: "60"
        val duration = host.field("Minutes", durationValue).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val reminderValue = existing?.reminderAt?.let { ((existing.startsAt - it) / 60_000).toString() } ?: "0"
        val reminder = host.field("Remind minutes before (0 = off)", reminderValue).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val dateButton = host.button(day.toString()) {}
        dateButton.setOnClickListener {
            DatePickerDialog(host.activity, { _, year, month, date ->
                day = LocalDate.of(year, month + 1, date); dateButton.text = day.toString()
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }
        val clock = DateTimeFormatter.ofPattern("h:mm a")
        val timeButton = host.button(time.format(clock)) {}
        timeButton.setOnClickListener {
            TimePickerDialog(host.activity, { _, hour, minute ->
                time = LocalTime.of(hour, minute); timeButton.text = time.format(clock)
            }, time.hour, time.minute, false).show()
        }
        fields.addView(title); fields.addView(host.text("Type")); fields.addView(kind)
        fields.addView(host.text("Staff member")); fields.addView(artist)
        fields.addView(host.text("Client record")); fields.addView(clients)
        fields.addView(dateButton); fields.addView(timeButton); fields.addView(duration)
        fields.addView(room); fields.addView(project); fields.addView(reminder)
        fields.addView(host.text("Times use ${zone.id}. Hours, time off and other entries are checked here.", 13f))
        val dialog = AlertDialog.Builder(host.activity).setTitle(if (existing == null) "New calendar entry" else "Edit calendar entry")
            .setView(host.body(fields)).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = duration.text.toString().toLongOrNull()
                val before = reminder.text.toString().toLongOrNull()
                if (minutes == null || minutes !in 1..1440 || before == null || before !in 0..10080) {
                    host.onError("Use one to 1440 minutes and a reminder up to seven days."); return@setOnClickListener
                }
                val local = day.atTime(time)
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.size != 1) {
                    host.onError("This time is skipped or repeated by daylight saving. Choose another time."); return@setOnClickListener
                }
                val startsAt = local.atOffset(offsets.single()).toInstant().toEpochMilli()
                val candidate = CalendarProposal(title.text.toString(),
                    if (kind.selectedItemPosition == 0) "appointment" else "task",
                    staff[artist.selectedItemPosition].id, startsAt, startsAt + minutes * 60_000, zone.id,
                    room.text.toString(), contacts.getOrNull(clients.selectedItemPosition - 1)?.id,
                    project.text.toString().trim().ifEmpty { null },
                    if (before == 0L) null else startsAt - before * 60_000)
                host.load({ if (existing == null) store.schedule(candidate)
                    else store.edit(existing.id, candidate, existing.revision) }) { result ->
                    when (result) {
                        is ScheduleResult.Saved -> { dialog.dismiss(); onSaved() }
                        is ScheduleResult.Conflict -> host.onError(
                            (if (result.sharedRoom) "Room occupied" else "Staff booked") +
                                " by ${result.title}. Choose another time.",
                        )
                        is ScheduleResult.Unavailable -> host.onError(result.reason)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun spinner(values: List<String>) = Spinner(host.activity).apply {
        adapter = object : ArrayAdapter<String>(host.activity, android.R.layout.simple_spinner_dropdown_item, values) {
            private fun color(view: View) = (view as TextView).apply {
                setTextColor(host.ink); setBackgroundColor(host.paper)
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                color(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                color(super.getDropDownView(position, convertView, parent))
        }
    }
}
