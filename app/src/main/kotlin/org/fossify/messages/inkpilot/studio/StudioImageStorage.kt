package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

private const val UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private const val COPY_BUFFER_BYTES = 32_768
private const val MAX_ORIGINAL_BYTES = 67_108_864L
private val IMPORT_JOURNAL = Regex("import-($UUID_TEXT)-($UUID_TEXT)\\.journal")
private val IMPORT_PART = Regex("import-($UUID_TEXT)-($UUID_TEXT)\\.part")
internal val studioImageImportSession: String = UUID.randomUUID().toString()

internal fun StudioAlbumStore.hasOriginalFilename(filename: String): Boolean = db.rawQuery(
    "SELECT 1 FROM studio_images WHERE filename=? LIMIT 1", arrayOf(filename),
).use { it.moveToFirst() }

/** Never infer ownership from a bare image name: only an old marker can identify an orphan. */
internal fun recoverStudioImageImports(directory: File, store: StudioAlbumStore) {
    val owned = directory.canonicalFile
    owned.listFiles().orEmpty().forEach { recoverJournal(it, owned, store) }
    owned.listFiles().orEmpty().filter { isPreviousPart(it, owned) }.forEach(File::delete)
}

private fun recoverJournal(journal: File, owned: File, store: StudioAlbumStore) {
    val match = IMPORT_JOURNAL.matchEntire(journal.name) ?: return
    if (match.groupValues[1] == studioImageImportSession || !journal.isFile) return
    val filename = "${match.groupValues[2]}.image"
    val original = File(owned, filename)
    val ownedFile = runCatching { original.canonicalFile.parentFile == owned && original.isFile }
        .getOrDefault(false)
    if (store.hasOriginalFilename(filename)) journal.delete()
    else if (!original.exists() || ownedFile && original.delete()) journal.delete()
}

private fun isPreviousPart(file: File, owned: File): Boolean {
    val match = IMPORT_PART.matchEntire(file.name) ?: return false
    return match.groupValues[1] != studioImageImportSession && file.isFile &&
        runCatching { file.canonicalFile.parentFile == owned }.getOrDefault(false)
}

internal fun studioImageSourceName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Reference image"

internal fun copyStudioImageSource(context: Context, uri: Uri, temporary: File) {
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "This image could not be opened." }
        FileOutputStream(temporary).use { output ->
            copyBoundedBytes(input, output)
            output.fd.sync()
        }
    }
}

private fun copyBoundedBytes(input: InputStream, output: FileOutputStream) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_ORIGINAL_BYTES) { "Choose an image smaller than 64 MB." }
        output.write(buffer, 0, count)
    }
}
