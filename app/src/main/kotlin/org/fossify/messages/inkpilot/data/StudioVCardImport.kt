package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

private const val MAX_VCARD_CONTACT_NAME_LENGTH = 256

/** Importing a shared card is a staff decision. Existing identities are never renamed here. */
sealed interface VCardImportResult {
    data class Added(val contact: StudioContact) : VCardImportResult
    data object NeedsReview : VCardImportResult
}

fun StudioRepository.importVCardContact(number: String, displayName: String): VCardImportResult =
    database.transaction {
        val normalized = canonicalStudioNumber(number)
        require(displayName.length <= MAX_VCARD_CONTACT_NAME_LENGTH)
        if (hasVCardAddressCandidate(normalized))
            VCardImportResult.NeedsReview
        else VCardImportResult.Added(upsertStudioContact(normalized, displayName))
    }

/** A shared card has no trusted region, so defer if any Studio identity claims either NANP form. */
private fun SQLiteDatabase.hasVCardAddressCandidate(number: String): Boolean {
    val addresses = when {
        number.matches(Regex("[0-9]{10}")) -> listOf(number, "+1$number")
        number.matches(Regex("\\+1[0-9]{10}")) -> listOf(number, number.drop(2))
        else -> listOf(number)
    }
    return addresses.any { address ->
        contactByNumber(address) != null ||
            rawQuery("SELECT 1 FROM contact_aliases WHERE address = ? LIMIT 1", arrayOf(address)).use {
                it.moveToFirst()
            }
    }
}
