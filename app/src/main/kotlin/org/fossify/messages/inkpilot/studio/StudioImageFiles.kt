package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val IMPORT_JOURNAL = Regex("import-($UUID_TEXT)-($UUID_TEXT)\\.journal")
private val IMPORT_PART = Regex("import-($UUID_TEXT)-($UUID_TEXT)\\.part")
private val IMPORT_SESSION = UUID.randomUUID().toString()

internal class StudioImageFiles(private val context: Context, private val store: StudioAlbumStore) {
    private val directory get() = File(context.filesDir, "studio-images").apply { mkdirs() }

    fun import(uri: Uri, albumId: Long): Long = synchronized(importLock) { copyOriginal(uri, albumId, null) }

    /** Own a private copy: future reads never depend on a transient provider URI grant. */
    fun importMmsOriginal(uri: Uri, link: StudioImageLink): Long = synchronized(importLock) {
        require(link.sourceMessageKey?.origin == org.fossify.messages.messaging.MessageOrigin.MMS)
        importLinkedOriginal(uri, link)
    }

    fun importReferenceOriginal(uri: Uri, link: StudioImageLink): Long = synchronized(importLock) {
        require(link.sourceMessageKey == null)
        importLinkedOriginal(uri, link)
    }

    private fun importLinkedOriginal(uri: Uri, link: StudioImageLink): Long {
        store.originalForSource(uri.toString())?.let { id -> store.linkOriginal(id, link); return id }
        return copyOriginal(uri, null, link)
    }

    /** Only previous-process journals can identify a final file abandoned before its DB row. */
    internal fun recoverIncompleteImports(activeSession: String = IMPORT_SESSION) = synchronized(importLock) {
        val owned = directory.canonicalFile
        owned.listFiles().orEmpty().forEach { journal ->
            val match = IMPORT_JOURNAL.matchEntire(journal.name) ?: return@forEach
            if (match.groupValues[1] == activeSession || !journal.isFile) return@forEach
            val filename = "${match.groupValues[2]}.image"
            val original = File(owned, filename)
            val ownedFile = runCatching { original.canonicalFile.parentFile == owned && original.isFile }
                .getOrDefault(false)
            if (store.hasOriginalFilename(filename)) journal.delete()
            else if (!original.exists() || ownedFile && original.delete()) journal.delete()
        }
        owned.listFiles().orEmpty().filter { file ->
            val match = IMPORT_PART.matchEntire(file.name)
            match != null && match.groupValues[1] != activeSession && file.isFile &&
                runCatching { file.canonicalFile.parentFile == owned }.getOrDefault(false)
        }.forEach(File::delete)
    }

    private fun copyOriginal(uri: Uri, albumId: Long?, link: StudioImageLink?): Long {
        recoverIncompleteImports()
        val mime = context.contentResolver.getType(uri)
        require(mime?.startsWith("image/") == true) { "Choose an image." }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Reference image"
        val temporary = File(directory, "import-$IMPORT_SESSION-${UUID.randomUUID()}.part")
        check(temporary.createNewFile()) { "The image could not be saved." }
        val original = File(directory, "${UUID.randomUUID()}.image")
        val journal = File(directory, "import-$IMPORT_SESSION-${original.name.removeSuffix(".image")}.journal")
        var recorded = false
        try {
            FileOutputStream(journal).use { output -> output.write(1); output.fd.sync() }
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "This image could not be opened." }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64L * 1024 * 1024) { "Choose an image smaller than 64 MB." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000) { "This image format or size is not supported." }
            check(temporary.renameTo(original)) { "The image could not be saved." }
            val imageId = if (link == null) store.addOriginal(requireNotNull(albumId), original.name, name)
            else store.saveMmsOriginal(uri.toString(), original.name, name,
                requireNotNull(mime), original.length(), bounds.outWidth, bounds.outHeight, link)
            recorded = store.hasOriginalFilename(original.name)
            // A competing process could have linked an existing source while we copied bytes.
            if (!recorded) original.delete()
            return imageId
        } catch (error: Exception) {
            if (runCatching { !store.hasOriginalFilename(original.name) }.getOrDefault(false)) original.delete()
            throw error
        } finally {
            temporary.delete()
            if (recorded || !original.exists()) journal.delete()
        }
    }

    companion object { private val importLock = Any() }

    fun originalFile(image: StudioImage): File {
        val file = File(directory, image.filename)
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid image location." }
        return file
    }

    fun thumbnail(image: StudioImage, size: Int = 600): Bitmap? {
        val file = originalFile(image)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
