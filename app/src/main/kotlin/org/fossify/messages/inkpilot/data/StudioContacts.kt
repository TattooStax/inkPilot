package org.fossify.messages.inkpilot.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.telephony.PhoneNumberUtils
import inkpilot.agent.ContactMemory
import java.util.UUID

internal fun canonicalStudioNumber(raw: String): String {
    require(raw.isNotBlank() && raw.length <= 64) { "A phone number is required" }
    require(raw.all { it.isDigit() || it in "+(). -\u00a0" }) { "Unsupported phone number" }
    return PhoneNumberUtils.normalizeNumber(raw).also {
        require(it.matches(Regex("\\+?[0-9]{1,32}"))) { "Unsupported phone number" }
    }
}

private const val CONTACT_COLUMNS = "id, number, display_name, memory, paused, revision, latest_event_id, last_message_at"

private fun Cursor.contact() = StudioContact(
    getString(0), getString(1), getString(2), getString(3), getInt(4) != 0, getLong(5),
    if (isNull(6)) null else getString(6), getLong(7),
)

internal fun SQLiteDatabase.studioContacts(): List<StudioContact> =
    rawQuery("SELECT $CONTACT_COLUMNS FROM studio_contacts ORDER BY display_name COLLATE NOCASE, id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.contact()) }
    }

internal fun SQLiteDatabase.studioContact(id: String): StudioContact? =
    rawQuery("SELECT $CONTACT_COLUMNS FROM studio_contacts WHERE id = ?", arrayOf(id)).use {
        if (it.moveToFirst()) it.contact() else null
    }

internal fun SQLiteDatabase.contactByNumber(number: String): StudioContact? =
    rawQuery("SELECT $CONTACT_COLUMNS FROM studio_contacts WHERE number = ?", arrayOf(number)).use {
        if (it.moveToFirst()) it.contact() else null
    }

internal fun SQLiteDatabase.upsertStudioContact(number: String, displayName: String): StudioContact {
    val normalized = canonicalStudioNumber(number)
    require(displayName.length <= 256) { "Contact name is too long" }
    val name = displayName.trim().ifEmpty { normalized }
    return transaction {
        val existing = contactByNumber(normalized)
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (existing == null) {
            insertOrThrow("studio_contacts", null, ContentValues().apply {
                put("id", id); put("number", normalized); put("display_name", name)
            })
        } else if (existing.displayName != name) {
            execSQL("UPDATE studio_contacts SET display_name = ?, revision = revision + 1 WHERE id = ?", arrayOf(name, id))
        }
        checkNotNull(studioContact(id))
    }
}

internal fun SQLiteDatabase.saveStudioMemory(id: String, text: String) {
    require(text.length <= 8_000) { "Memory is limited to 8,000 characters" }
    transaction {
        val contact = requireNotNull(studioContact(id)) { "Contact not found" }
        if (contact.memory != text) {
            execSQL("UPDATE studio_contacts SET memory = ?, revision = revision + 1 WHERE id = ?", arrayOf(text, id))
        }
    }
}

internal fun SQLiteDatabase.setStudioPaused(id: String, paused: Boolean) {
    transaction {
        val contact = requireNotNull(studioContact(id)) { "Contact not found" }
        if (contact.paused != paused) {
            execSQL("UPDATE studio_contacts SET paused = ?, revision = revision + 1 WHERE id = ?", arrayOf(if (paused) 1 else 0, id))
        }
    }
}

internal fun SQLiteDatabase.studioMemory(id: String): List<ContactMemory> =
    studioContact(id)?.memory?.takeIf { it.isNotBlank() }?.let { listOf(ContactMemory(id, it)) }.orEmpty()
