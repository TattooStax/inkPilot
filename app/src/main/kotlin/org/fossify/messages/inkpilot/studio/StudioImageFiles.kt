package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal class StudioImageFiles(private val context: Context, private val store: StudioAlbumStore) {
    private val directory get() = File(context.filesDir, "studio-images").apply { mkdirs() }

    fun import(uri: Uri, albumId: Long): Long = copyOriginal(uri, albumId, null)

    /** Own a private copy: future reads never depend on a transient provider URI grant. */
    fun importMmsOriginal(uri: Uri, link: StudioImageLink): Long = synchronized(importLock) {
        require(link.sourceMessageKey.origin == org.fossify.messages.messaging.MessageOrigin.MMS)
        store.originalForSource(uri.toString())?.let { id -> store.linkOriginal(id, link); return@synchronized id }
        copyOriginal(uri, null, link)
    }

    private fun copyOriginal(uri: Uri, albumId: Long?, link: StudioImageLink?): Long {
        val mime = context.contentResolver.getType(uri)
        require(mime?.startsWith("image/") == true) { "Choose an image." }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Reference image"
        val temporary = File.createTempFile("import-", ".part", directory)
        var original: File? = null
        try {
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
            original = File(directory, "${UUID.randomUUID()}.image")
            check(temporary.renameTo(original)) { "The image could not be saved." }
            return if (link == null) store.addOriginal(requireNotNull(albumId), original.name, name)
            else store.saveMmsOriginal(uri.toString(), original.name, name,
                requireNotNull(mime), original.length(), bounds.outWidth, bounds.outHeight, link)
        } catch (error: Exception) {
            original?.delete()
            throw error
        } finally {
            temporary.delete()
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
