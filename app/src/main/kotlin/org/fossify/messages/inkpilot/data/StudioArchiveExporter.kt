package org.fossify.messages.inkpilot.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Plaintext, export-only Studio snapshot. The manifest is written last so an interrupted ZIP
 * cannot be mistaken for a complete archive. Native SMS/MMS, app settings, and model weights
 * are outside this format; there is deliberately no restore action yet.
 */
internal class StudioArchiveExporter(private val context: Context, private val repository: StudioRepository) {
    data class Summary(val tables: Int, val originals: Int)
    private data class Entry(val name: String, val bytes: Long, val sha256: String, val rows: Long? = null) {
        fun json() = JSONObject().put("name", name).put("bytes", bytes).put("sha256", sha256).apply {
            rows?.let { put("rows", it) }
        }
    }

    fun export(uri: Uri, unlocked: () -> Boolean): Summary {
        var complete = false
        try {
            requireUnlocked(unlocked)
            val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                "The chosen document could not be opened."
            }
            val result = output.use { export(it, unlocked) }
            complete = true
            return result
        } finally {
            if (!complete) {
                // A provider may refuse deletion. Persist a generic warning for the next unlock.
                StudioArchiveCleanupNotice(context).recordIfDeletionFails {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
            }
        }
    }

    fun export(output: OutputStream, unlocked: () -> Boolean = { true }): Summary {
        requireUnlocked(unlocked)
        val entries = JSONArray()
        val tableDescriptions = JSONArray()
        var tableCount = 0
        var imageNames = emptyList<String>()
        var schemaVersion = 0
        var originalCount = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(ZIP_COMPRESSION_LEVEL)
            val db = repository.database
            db.beginTransactionNonExclusive()
            try {
                schemaVersion = db.version
                val tables = db.rawQuery(
                    "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                    }
                }
                tables.forEach { (name, sql) ->
                    require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid table name." }
                    val columns = JSONArray()
                    requireUnlocked(unlocked)
                    val entry = writeTable(zip, db, name, columns, unlocked)
                    entries.put(entry.json())
                    tableDescriptions.put(JSONObject().put("name", name).put("sql", sql)
                        .put("columns", columns).put("entry", entry.name))
                    tableCount++
                }
                imageNames = db.rawQuery("SELECT filename FROM studio_images ORDER BY filename", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                check(imageNames.size == imageNames.toSet().size) { "Duplicate original filename." }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            // Imports create the private original before committing its row, and originals are
            // retained even when album membership is removed. Any missing file aborts export.
            imageNames.forEach { filename ->
                requireUnlocked(unlocked)
                entries.put(writeOriginal(zip, filename, unlocked).json())
                originalCount++
            }
            requireUnlocked(unlocked)
            val manifest = JSONObject()
                .put("format", "inkpilot-studio-export-v1")
                .put("studioSchemaVersion", schemaVersion)
                .put("scope", "Studio database rows and retained reference originals only")
                .put("plaintext", true)
                .put("tables", tableDescriptions)
                .put("entries", entries)
                .put("originalCount", originalCount)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write((manifest.toString() + "\n").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        requireUnlocked(unlocked)
        return Summary(tableCount, originalCount)
    }

    private fun writeTable(zip: ZipOutputStream, db: SQLiteDatabase, table: String, columns: JSONArray,
                           unlocked: () -> Boolean): Entry {
        val name = "studio/tables/" + table + ".jsonl"
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var rows = 0L
        zip.putNextEntry(ZipEntry(name))
        db.rawQuery("SELECT * FROM \"" + table + "\"", null).use { cursor ->
            cursor.columnNames.forEach(columns::put)
            while (cursor.moveToNext()) {
                requireUnlocked(unlocked)
                val row = JSONArray()
                for (i in 0 until cursor.columnCount) row.put(cell(cursor, i))
                val line = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
                zip.write(line)
                digest.update(line)
                bytes += line.size
                rows++
            }
        }
        zip.closeEntry()
        return Entry(name, bytes, digest.hex(), rows)
    }

    private fun cell(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> JSONObject().put("type", "integer").put("value", cursor.getLong(index).toString())
        Cursor.FIELD_TYPE_FLOAT -> JSONObject().put("type", "real").put("value", cursor.getDouble(index).toString())
        Cursor.FIELD_TYPE_STRING -> JSONObject().put("type", "text").put("value", cursor.getString(index))
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("type", "base64").put("value",
            Base64.encodeToString(cursor.getBlob(index).also {
                require(it.size <= MAX_BLOB_BYTES) { "Studio row is too large to export." }
            }, Base64.NO_WRAP))
        else -> error("Unsupported database value.")
    }

    private fun writeOriginal(zip: ZipOutputStream, filename: String, unlocked: () -> Boolean): Entry {
        require(filename.matches(Regex("[A-Za-z0-9._-]+")) && filename != "." && filename != "..") {
            "Invalid original filename."
        }
        val directory = File(context.filesDir, "studio-images").canonicalFile
        val file = File(directory, filename).canonicalFile
        check(file.parentFile == directory && file.isFile) { "A Studio original is missing." }
        val name = "studio/images/" + filename
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                requireUnlocked(unlocked)
                val size = input.read(buffer)
                if (size < 0) break
                zip.write(buffer, 0, size)
                digest.update(buffer, 0, size)
                bytes += size
            }
        }
        zip.closeEntry()
        check(bytes == file.length()) { "A Studio original changed during export." }
        return Entry(name, bytes, digest.hex())
    }

    private fun requireUnlocked(unlocked: () -> Boolean) {
        check(unlocked()) { "Studio export stopped because inkPilot is locked." }
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    companion object {
        private const val BYTE_MASK = 0xff
        private const val ZIP_COMPRESSION_LEVEL = 1
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    }
}
