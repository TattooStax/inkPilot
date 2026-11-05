package org.fossify.messages.inkpilot.studio

import androidx.appcompat.app.AlertDialog
import java.time.ZoneId

/** Occasional calendar administration stays in a Back-dismissible contextual card. */
internal class CalendarSettingsCard(
    private val host: StudioPanelHost,
    private val zone: ZoneId,
    private val availability: () -> Unit,
    private val addStaff: () -> Unit,
    private val editZone: () -> Unit,
) {
    fun show() {
        var selected: (() -> Unit)? = null
        val actions = arrayOf("Staff hours and time off", "Add staff member", "Studio time zone · ${zone.id}")
        AlertDialog.Builder(host.activity).setTitle("Calendar settings")
            .setItems(actions) { _, index ->
                selected = when (index) {
                    0 -> availability
                    1 -> addStaff
                    else -> editZone
                }
            }.setNegativeButton("Close", null).create().apply {
                setOnDismissListener { selected?.let(host::onUi) }
            }.show()
    }
}
