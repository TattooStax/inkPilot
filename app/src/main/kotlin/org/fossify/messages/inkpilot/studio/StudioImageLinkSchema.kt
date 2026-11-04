package org.fossify.messages.inkpilot.studio

import android.database.sqlite.SQLiteDatabase
import org.fossify.messages.messaging.MessageKey

/** Project IDs are opaque until the project store is introduced. */
internal data class StudioImageLink(
    val contactId: String, val sourceMessageKey: MessageKey, val threadId: Long,
    val projectId: String? = null, val staffId: Long? = null, val appointmentId: Long? = null,
)

internal object StudioImageLinkSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE image_links (
            image_id INTEGER NOT NULL REFERENCES studio_images(id),
            contact_id TEXT NOT NULL REFERENCES studio_contacts(id), project_id TEXT,
            staff_id INTEGER REFERENCES studio_staff(id), appointment_id INTEGER REFERENCES studio_calendar(id),
            source_origin TEXT NOT NULL CHECK(source_origin='MMS'), source_id INTEGER NOT NULL CHECK(source_id>=0),
            thread_id INTEGER NOT NULL CHECK(thread_id>=0),
            PRIMARY KEY(image_id,contact_id,source_origin,source_id))""")
        db.execSQL("CREATE INDEX image_links_contact ON image_links(contact_id,image_id)")
        db.execSQL("CREATE INDEX image_links_thread ON image_links(thread_id,image_id)")
        db.execSQL("""CREATE TABLE image_original_sources (
            source_uri TEXT PRIMARY KEY NOT NULL, image_id INTEGER NOT NULL UNIQUE REFERENCES studio_images(id),
            mime_type TEXT NOT NULL, byte_count INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
            ownership TEXT NOT NULL CHECK(ownership='PRIVATE_COPY'))""")
    }
}
