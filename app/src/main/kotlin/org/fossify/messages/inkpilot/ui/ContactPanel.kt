package org.fossify.messages.inkpilot.ui

import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.StudioContact
import org.fossify.messages.inkpilot.data.StudioRepository

internal class ContactPanel(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
) {
    fun view(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.button(activity, "Add contact", ::addContact).apply { id = R.id.inkpilot_add_contact })
        val list = StudioUi.column(activity, 0).apply { id = R.id.inkpilot_contact_list }
        list.addView(StudioUi.text(activity, "Loading contacts…"))
        body.addView(list)
        val scroll = StudioUi.scroll(activity, body, loading = true)
        work.load(repository::contacts) { contacts ->
            list.removeAllViews()
            if (contacts.isEmpty()) list.addView(StudioUi.text(activity, "Add a client or receive a message to start."))
            contacts.forEach { contact ->
                val row = StudioUi.column(activity, 0)
                row.addView(StudioUi.text(activity, contact.displayName.ifBlank { contact.number }, 18f, true))
                row.addView(StudioUi.text(activity, contact.number, 14f))
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { edit(contact) }
                list.addView(row)
                list.addView(StudioUi.rule(activity))
            }
            scroll.contentReady()
        }
        return scroll
    }

    private fun edit(contact: StudioContact) {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, contact.number, 15f))
        body.addView(StudioUi.text(activity, "Client memory", 18f, true))
        body.addView(StudioUi.text(activity, "Facts staff want the assistant to remember. Edit or clear them any time.", 14f))
        val memory = EditText(activity).apply {
            id = R.id.inkpilot_contact_memory
            setText(contact.memory)
            hint = "Preferences, projects, and useful notes"
            minLines = 5
            maxLines = 10
            filters = arrayOf(InputFilter.LengthFilter(8_000))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(StudioUi.ink)
        }
        val paused = SwitchCompat(activity).apply {
            id = R.id.inkpilot_contact_paused
            text = "Pause drafts for this client"
            isChecked = contact.paused
            setTextColor(StudioUi.ink)
        }
        body.addView(memory)
        body.addView(paused)
        body.addView(StudioUi.button(activity, "Call client") {
            runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null))) }
                .onFailure { StudioUi.error(activity, "No phone dialer is available.") }
        })
        AlertDialog.Builder(activity).setTitle(contact.displayName.ifBlank { "Client" })
            .setView(StudioUi.scroll(activity, body)).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val text = memory.text.toString()
                val pause = paused.isChecked
                work.load({ repository.saveMemory(contact.id, text); repository.setPaused(contact.id, pause) }) { refresh() }
            }.show()
    }

    private fun addContact() {
        val body = StudioUi.column(activity)
        val name = EditText(activity).apply {
            hint = "Client name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(StudioUi.ink); setHintTextColor(StudioUi.muted)
        }
        val number = EditText(activity).apply {
            hint = "Phone number"; inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(StudioUi.ink); setHintTextColor(StudioUi.muted)
        }
        body.addView(name)
        body.addView(number)
        val dialog = AlertDialog.Builder(activity).setTitle("Add contact").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (number.text.isBlank()) { number.error = "Enter a phone number"; return@setOnClickListener }
                val phone = number.text.toString()
                val label = name.text.toString().trim()
                work.load({ repository.upsertContact(phone, label) }) { dialog.dismiss(); refresh() }
            }
        }
        dialog.show()
    }
}
