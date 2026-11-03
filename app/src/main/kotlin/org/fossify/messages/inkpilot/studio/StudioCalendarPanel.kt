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
    private var day = LocalDate.now()
    private var content = host.column()
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

    fun view(): View {
        content = host.column()
        render()
        return host.body(content)
    }

    private fun render() {
        content.removeAllViews()
        content.addView(host.text("Calendar", 22f))
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
            host.load({ store.staff() }) { staff ->
                if (staff.isEmpty()) addStaff() else CalendarEditor(host, store, day, staff, ::render).show()
            }
        })
        content.addView(host.button("Add staff member") { addStaff() })
        content.addView(host.text("Local studio calendar · ${ZoneId.systemDefault().id}", 13f))
        val entries = host.column()
        content.addView(entries)
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        host.load({ store.entries(start, end) }) { rows ->
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

    private fun addStaff() {
        val name = host.field("Staff name")
        val dialog = AlertDialog.Builder(host.activity).setTitle("Add staff member").setView(name)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString()
                host.load({ store.addStaff(value) }) { dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }

    private fun details(entry: CalendarEntry) {
        val message = "${entry.staffName}\n${entry.kind.replaceFirstChar { it.uppercase() }}" +
            (if (entry.room.isBlank()) "" else "\n${entry.room}") + "\n${entry.status.replaceFirstChar { it.uppercase() }}"
        val dialog = AlertDialog.Builder(host.activity).setTitle(entry.title).setMessage(message).setNegativeButton("Close", null)
        if (entry.status == "scheduled") {
            dialog.setPositiveButton("Complete") { _, _ ->
                host.load({ store.setStatus(entry.id, "completed") }) { render() }
            }
            dialog.setNeutralButton("Cancel entry") { _, _ ->
                AlertDialog.Builder(host.activity).setTitle("Cancel ${entry.title}?")
                    .setPositiveButton("Cancel entry") { _, _ ->
                        host.load({ store.setStatus(entry.id, "cancelled") }) { render() }
                    }.setNegativeButton("Keep", null).show()
            }
        }
        dialog.show()
    }
}
