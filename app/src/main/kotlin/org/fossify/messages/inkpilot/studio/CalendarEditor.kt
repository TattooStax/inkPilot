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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class CalendarEditor(
    private val host: StudioPanelHost,
    private val store: StudioCalendarStore,
    initialDay: LocalDate,
    private val staff: List<StudioStaff>,
    private val onSaved: () -> Unit,
) {
    private var day = initialDay
    private var time = LocalTime.of(12, 0)

    fun show() {
        val fields = host.column().apply { setPadding(host.dp(20), 0, host.dp(20), 0) }
        val title = host.field("Title")
        val artist = spinner(staff.map { it.name })
        val kind = spinner(listOf("Appointment", "Task"))
        val room = host.field("Room (leave empty if none)", "Studio")
        val duration = host.field("Minutes", "60").apply { inputType = InputType.TYPE_CLASS_NUMBER }
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
        fields.addView(dateButton); fields.addView(timeButton); fields.addView(duration); fields.addView(room)
        fields.addView(host.text("Times use ${ZoneId.systemDefault().id}. External bookings are not included.", 13f))
        val dialog = AlertDialog.Builder(host.activity).setTitle("New calendar entry").setView(host.body(fields))
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = duration.text.toString().toLongOrNull()
                if (minutes == null || minutes !in 1..1440) {
                    host.onError("Choose a duration between one minute and 24 hours."); return@setOnClickListener
                }
                val local = day.atTime(time)
                val zone = ZoneId.systemDefault()
                val zoned = local.atZone(zone)
                if (zoned.toLocalDateTime() != local) {
                    host.onError("That time falls within a daylight-saving change. Choose another time."); return@setOnClickListener
                }
                val startsAt = zoned.toInstant().toEpochMilli()
                val proposal = CalendarProposal(title.text.toString(), if (kind.selectedItemPosition == 0) "appointment" else "task",
                    staff[artist.selectedItemPosition].id, startsAt, startsAt + minutes * 60_000, zone.id, room.text.toString())
                host.load({ store.schedule(proposal) }) { result ->
                    when (result) {
                        is ScheduleResult.Saved -> { dialog.dismiss(); onSaved() }
                        is ScheduleResult.Conflict -> host.onError(
                            (if (result.sharedRoom) "That room is occupied" else "That staff member is booked") +
                                " by ${result.title}. Choose another time.",
                        )
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
