package org.fossify.messages.inkpilot.data

import android.database.sqlite.SQLiteDatabase

/** OpenHelper wraps creation and the complete v1 -> v2 -> v3 chain in a transaction. */
internal object StudioIdentitySchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE contact_aliases (
            address TEXT NOT NULL, region TEXT NOT NULL, contact_id TEXT NOT NULL REFERENCES studio_contacts(id),
            review_state TEXT NOT NULL CHECK(review_state IN ('PENDING','REVIEWED')),
            PRIMARY KEY(address,region,contact_id))""")
        db.execSQL("""CREATE TABLE memory_entries (
            contact_id TEXT PRIMARY KEY NOT NULL REFERENCES studio_contacts(id), source TEXT NOT NULL,
            review_state TEXT NOT NULL CHECK(review_state IN ('PENDING','REVIEWED')), body TEXT NOT NULL)""")
        db.execSQL("INSERT INTO contact_aliases SELECT number,'',id,'PENDING' FROM studio_contacts")
        db.execSQL("INSERT INTO memory_entries SELECT id,'Legacy staff note','PENDING',memory FROM studio_contacts WHERE memory != ''")
    }
}

enum class IdentityReviewState { PENDING, REVIEWED }
data class ContactAlias(val address: String, val region: String, val contactId: String, val reviewState: IdentityReviewState)
data class MemoryEntry(val contactId: String, val source: String, val reviewState: IdentityReviewState, val body: String)
sealed class ContactMatch {
    data class Unique(val contactId: String) : ContactMatch()
    data class Ambiguous(val contactIds: List<String>) : ContactMatch()
    object Missing : ContactMatch()
}

class ContactResolver(private val repository: StudioRepository) {
    fun resolve(address: String, region: String): ContactMatch = repository.resolveContact(address, region)
}

internal fun SQLiteDatabase.resolveStudioContact(address: String, region: String): ContactMatch {
    val number = canonicalStudioNumber(address)
    val variants = mutableSetOf(number)
    // These are candidates requiring review, never an implicit country-code rewrite.
    if (region.uppercase() in setOf("US", "CA")) {
        if (number.matches(Regex("[0-9]{10}"))) variants.add("+1$number")
        if (number.matches(Regex("\\+1[0-9]{10}"))) variants.add(number.drop(2))
    }
    val aliases = rawQuery("SELECT address,region,contact_id,review_state FROM contact_aliases", null).use { c ->
        buildList { while (c.moveToNext()) add(ContactAlias(c.getString(0), c.getString(1), c.getString(2), IdentityReviewState.valueOf(c.getString(3)))) }
    }
    val reviewed = aliases.filter { it.address == number && it.region == region.uppercase() && it.reviewState == IdentityReviewState.REVIEWED }.map { it.contactId }.distinct()
    if (reviewed.size == 1) return ContactMatch.Unique(reviewed.single())
    val candidates = aliases.filter { it.address in variants && (it.region.isEmpty() || it.region == region.uppercase()) }.map { it.contactId }.distinct()
    return when {
        reviewed.size > 1 -> ContactMatch.Ambiguous(reviewed)
        candidates.isEmpty() -> ContactMatch.Missing
        variants.size == 1 && candidates.size == 1 -> ContactMatch.Unique(candidates.single())
        else -> ContactMatch.Ambiguous(candidates)
    }
}
