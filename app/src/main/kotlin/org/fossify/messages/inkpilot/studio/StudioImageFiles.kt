package org.fossify.messages.inkpilot.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
    internal fun recoverIncompleteImports() = synchronized(importLock) { recoverStudioImageImports(directory, store) }

    private fun copyOriginal(uri: Uri, albumId: Long?, link: StudioImageLink?): Long {
        recoverIncompleteImports()
        val mime = context.contentResolver.getType(uri)
        require(mime?.startsWith("image/") == true) { "Choose an image." }
        val name = studioImageSourceName(context, uri)
        val temporary = File(directory, "import-$studioImageImportSession-${UUID.randomUUID()}.part")
        check(temporary.createNewFile()) { "The image could not be saved." }
        val original = File(directory, "${UUID.randomUUID()}.image")
        val journal = File(directory,
            "import-$studioImageImportSession-${original.name.removeSuffix(".image")}.journal")
        var recorded = false
        try {
            FileOutputStream(journal).use { output -> output.write(1); output.fd.sync() }
            copyStudioImageSource(context, uri, temporary)
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
