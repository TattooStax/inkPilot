package org.fossify.messages.inkpilot.ui

import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.fossify.messages.R
import org.fossify.messages.inkpilot.data.MemoryEntry
import org.fossify.messages.inkpilot.data.StudioContact
import org.fossify.messages.inkpilot.data.StudioRepository

internal class ContactPanel(
    private val activity: AppCompatActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val refresh: () -> Unit,
    private val openImages: (String) -> Unit = {},
    private val openPhone: (String) -> Unit = {},
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
                row.setOnClickListener { work.load({ repository.memoryEntry(contact.id) }) { edit(contact, it) } }
                list.addView(row)
                list.addView(StudioUi.rule(activity))
            }
            scroll.contentReady()
        }
        return scroll
    }

    private fun edit(contact: StudioContact, entry: MemoryEntry?) {
        lateinit var dialog: AlertDialog
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
        val source = EditText(activity).apply {
            hint = "Memory source (for example: consultation with client)"
            setText(entry?.source ?: "Staff note")
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        if (entry != null) body.addView(StudioUi.text(activity, "Memory review: ${entry.reviewState}"))
        body.addView(source)
        body.addView(StudioUi.button(activity, "Review phone identity") { reviewIdentity(contact) })
        body.addView(StudioUi.button(activity, "Forget memory") {
            AlertDialog.Builder(activity).setMessage("Remove this client's stored memory?")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ ->
                    // Retire the editor before enqueueing removal. Its stale Save action must
                    // never run after the queued forget transaction and restore old memory.
                    dialog.dismiss()
                    work.load({ repository.forgetMemory(contact.id) }) { refresh() }
                }.show()
        })
        body.addView(StudioUi.button(activity, "Export memory") {
            work.load({ repository.exportMemory(contact.id) }) { text ->
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Export memory"))
            }
        })
        body.addView(memory)
        body.addView(paused)
        body.addView(StudioUi.button(activity, "Call client") { dialog.dismiss(); openPhone(contact.number) })
        dialog = AlertDialog.Builder(activity).setTitle(contact.displayName.ifBlank { "Client" })
            .setView(StudioUi.scroll(activity, body)).setNegativeButton("Cancel", null)
            .setNeutralButton("Reference images") { _, _ -> openImages(contact.id) }
            .setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val text = memory.text.toString()
                val pause = paused.isChecked
                val provenance = source.text.toString()
                save.isEnabled = false
                listOf(memory, paused, source).forEach { it.isEnabled = false }
                work.load({ repository.saveContactEdit(contact.id, text, pause, provenance) },
                    failed = {
                        // Keep the editor and its typed fields so staff can retry or copy them.
                        save.isEnabled = true
                        listOf(memory, paused, source).forEach { it.isEnabled = true }
                        memory.error = "Could not save. Try again."
                    }) {
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun reviewIdentity(contact: StudioContact) {
        val body = StudioUi.column(activity)
        val address = EditText(activity).apply { hint = "Phone address"; setText(contact.number) }
        val region = EditText(activity).apply { hint = "Region (US, CA, or blank)" }
        body.addView(StudioUi.text(activity, "Confirm this address belongs to ${contact.displayName}. This does not merge message history."))
        body.addView(address); body.addView(region)
        AlertDialog.Builder(activity).setTitle("Review identity").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ ->
                val phone = address.text.toString(); val country = region.text.toString().trim()
                work.load({ repository.reviewAlias(phone, country, contact.id) }) { refresh() }
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
