package org.fossify.messages.inkpilot.studio

import android.content.ContentValues
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.transaction

internal class StudioAlbumStore(private val repository: StudioRepository) {
    private val db get() = repository.database

    fun albums(): List<StudioAlbum> = db.rawQuery(
        "SELECT id,name,artist FROM studio_albums ORDER BY name COLLATE NOCASE", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(StudioAlbum(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
    } }

    fun saveAlbum(id: Long?, name: String, artist: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 80) { "Enter an album name of 80 characters or fewer." }
        require(artist.trim().length <= 80) { "Keep the artist name under 80 characters." }
        val values = ContentValues().apply { put("name", trimmed); put("artist", artist.trim()) }
        return if (id == null) db.insertOrThrow("studio_albums", null, values) else {
            check(db.update("studio_albums", values, "id=?", arrayOf(id.toString())) == 1) { "Album no longer exists." }
            id
        }
    }

    fun images(albumId: Long?, offset: Int = 0): List<StudioImage> {
        require(offset >= 0)
        val membership = if (albumId == null) "" else
            "JOIN studio_album_images a ON i.id=a.image_id WHERE a.album_id=?"
        return db.rawQuery(
            "SELECT i.id,i.filename,i.display_name FROM studio_images i $membership ORDER BY i.imported_at DESC,i.id DESC LIMIT 100 OFFSET $offset",
            albumId?.let { arrayOf(it.toString()) },
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(StudioImage(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
        } }
    }

    fun addOriginal(albumId: Long, filename: String, displayName: String): Long = db.transaction {
        val values = ContentValues().apply {
            put("filename", filename); put("display_name", displayName.take(160))
            put("imported_at", System.currentTimeMillis())
        }
        val imageId = db.insertOrThrow("studio_images", null, values)
        addMembership(albumId, imageId)
        imageId
    }

    fun originalForSource(uri: String): Long? = db.rawQuery(
        "SELECT image_id FROM image_original_sources WHERE source_uri=?", arrayOf(uri),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun saveMmsOriginal(uri: String, filename: String, name: String, mime: String,
                        bytes: Long, width: Int, height: Int, link: StudioImageLink): Long = db.transaction {
        val existing = originalForSource(uri)
        val id = existing ?: run {
            val album = albums().firstOrNull { it.name == "Client References" }?.id
                ?: saveAlbum(null, "Client References", "")
            addOriginal(album, filename, name).also { imageId ->
                db.execSQL("INSERT INTO image_original_sources VALUES(?,?,?,?,?,?, 'PRIVATE_COPY')",
                    arrayOf<Any>(uri, imageId, mime, bytes, width, height))
            }
        }
        linkOriginal(id, link)
        id
    }

    fun linkOriginal(imageId: Long, link: StudioImageLink) {
        require(link.sourceMessageKey.origin == org.fossify.messages.messaging.MessageOrigin.MMS)
        require(link.sourceMessageKey.id >= 0 && link.threadId >= 0)
        val values = ContentValues().apply {
            put("image_id", imageId); put("contact_id", link.contactId); put("project_id", link.projectId)
            put("staff_id", link.staffId); put("appointment_id", link.appointmentId)
            put("source_origin", link.sourceMessageKey.origin.name); put("source_id", link.sourceMessageKey.id)
            put("thread_id", link.threadId)
        }
        db.transaction {
            val updated = db.update("image_links", values,
                "image_id=? AND contact_id=? AND source_origin=? AND source_id=?",
                arrayOf(imageId.toString(), link.contactId, link.sourceMessageKey.origin.name, link.sourceMessageKey.id.toString()))
            if (updated == 0) db.insertOrThrow("image_links", null, values)
        }
    }

    fun linkedImages(contactId: String? = null, threadId: Long? = null, offset: Int = 0): List<StudioImage> {
        require(offset >= 0 && (contactId != null || threadId != null))
        val filters = mutableListOf<String>(); val args = mutableListOf<String>()
        contactId?.let { filters.add("l.contact_id=?"); args.add(it) }
        threadId?.let { filters.add("l.thread_id=?"); args.add(it.toString()) }
        return db.rawQuery("""SELECT i.id,i.filename,i.display_name FROM studio_images i
            WHERE EXISTS(SELECT 1 FROM image_links l WHERE l.image_id=i.id AND ${filters.joinToString(" AND ")})
            ORDER BY i.imported_at DESC,i.id DESC LIMIT 100 OFFSET $offset""", args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(StudioImage(cursor.getLong(0), cursor.getString(1), cursor.getString(2))) }
        }
    }

    fun addMembership(albumId: Long, imageId: Long) {
        db.execSQL("INSERT OR IGNORE INTO studio_album_images(album_id,image_id) VALUES(?,?)", arrayOf(albumId, imageId))
    }

    /** The original record and original file deliberately survive membership removal. */
    fun removeMembership(albumId: Long, imageId: Long) {
        db.delete("studio_album_images", "album_id=? AND image_id=?", arrayOf(albumId.toString(), imageId.toString()))
    }
}
