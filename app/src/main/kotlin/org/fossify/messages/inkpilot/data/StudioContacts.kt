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

internal fun SQLiteDatabase.reviewedAddressContact(number: String): StudioContact? =
    rawQuery("SELECT contact_id FROM contact_aliases WHERE address = ? AND region = '' AND review_state = 'REVIEWED'", arrayOf(number)).use {
        if (it.moveToFirst()) studioContact(it.getString(0)) else null
    }

internal fun SQLiteDatabase.upsertStudioContact(number: String, displayName: String): StudioContact {
    val normalized = canonicalStudioNumber(number)
    require(displayName.length <= 256) { "Contact name is too long" }
    val label = displayName.trim()
    return transaction {
        val existing = reviewedAddressContact(normalized) ?: contactByNumber(normalized)
        val name = label.ifEmpty { existing?.displayName ?: normalized }
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (existing == null) {
            insertOrThrow("studio_contacts", null, ContentValues().apply {
                put("id", id); put("number", normalized); put("display_name", name)
            })
        } else if (existing.displayName != name) {
            execSQL("UPDATE studio_contacts SET display_name = ?, revision = revision + 1 WHERE id = ?", arrayOf(name, id))
        }
        execSQL("INSERT OR IGNORE INTO contact_aliases VALUES(?, ?, ?, ?)", arrayOf(normalized, "", id, "PENDING"))
        checkNotNull(studioContact(id))
    }
}

internal fun SQLiteDatabase.saveStudioMemory(id: String, text: String) {
    transaction { saveStudioContactEdit(id, text, requireNotNull(studioContact(id)).paused, "Staff note") }
}

internal fun SQLiteDatabase.saveStudioContactEdit(id: String, text: String, paused: Boolean, source: String) {
    require(text.length <= 8_000) { "Memory is limited to 8,000 characters" }
    require(source.isNotBlank() && source.length <= 256) { "A source is required (up to 256 characters)" }
    val reviewedSource = source.trim()
    transaction {
        val contact = requireNotNull(studioContact(id)) { "Contact not found" }
        val prior = studioMemoryEntry(id)
        val changed = contact.memory != text || contact.paused != paused
        val reviewChanged = prior?.source != reviewedSource || prior?.reviewState != IdentityReviewState.REVIEWED
        if (changed || reviewChanged) {
            if (text.isBlank()) delete("memory_entries", "contact_id = ?", arrayOf(id))
            else execSQL(
                "INSERT OR REPLACE INTO memory_entries VALUES(?, ?, 'REVIEWED', ?)",
                arrayOf(id, reviewedSource, text),
            )
            execSQL("UPDATE studio_contacts SET memory = ?, paused = ?, revision = revision + 1 WHERE id = ?", arrayOf<Any>(text, if (paused) 1 else 0, id))
        }
    }
}

internal fun SQLiteDatabase.studioMemoryEntry(id: String): MemoryEntry? =
    rawQuery("SELECT contact_id,source,review_state,body FROM memory_entries WHERE contact_id = ?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else MemoryEntry(it.getString(0), it.getString(1), IdentityReviewState.valueOf(it.getString(2)), it.getString(3))
    }

internal fun SQLiteDatabase.setStudioPaused(id: String, paused: Boolean) {
    transaction {
        val contact = requireNotNull(studioContact(id)) { "Contact not found" }
        if (contact.paused != paused) {
            execSQL("UPDATE studio_contacts SET paused = ?, revision = revision + 1 WHERE id = ?", arrayOf<Any>(if (paused) 1 else 0, id))
        }
    }
}

internal fun SQLiteDatabase.studioMemory(id: String): List<ContactMemory> =
    studioMemoryEntry(id)?.takeIf { it.reviewState == IdentityReviewState.REVIEWED && it.body.isNotBlank() }
        ?.let { listOf(ContactMemory(id, it.body)) }.orEmpty()
