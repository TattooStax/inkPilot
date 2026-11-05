package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val MAX_SHARED_IMAGES = 4
private const val MAX_SHARED_IMAGE_BYTES = 1_048_576
private const val COPY_BUFFER_BYTES = 8_192
private const val OWNED_PATH = "composer_shared_images"
private val OWNED_NAME = Regex("[0-9a-f-]{36}\\.(?:png|jpg)")
private val COPY_SESSION = UUID.randomUUID().toString()
private const val JOURNAL_SUFFIX = ".journal"

/** A share grant ends with its task; retain bounded image bytes in private app storage. */
internal fun Context.captureSharedComposerImages(uris: List<Uri>): List<Uri>? {
    if (uris.isEmpty()) return emptyList()
    if (uris.size > MAX_SHARED_IMAGES) return null
    val directory = File(filesDir, "composer-shares").apply { mkdirs() }
    // Only incomplete copies from earlier app processes are provably unused. A finalized
    // image may still be in an Activity route or an unresolved MMS, even if no draft lists it.
    directory.listFiles().orEmpty().filter { it.name.startsWith("share-") && it.name.endsWith(".part") &&
        !it.name.startsWith("share-$COPY_SESSION-") }.forEach(File::delete)
    val captured = mutableListOf<File>()
    return try {
        uris.map { source ->
            val extension = when (contentResolver.getType(source)) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> error("Unsupported shared image")
            }
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.$extension")
            // Commit the final name before any bytes can reach it. If this process dies
            // after rename but before ComposerStore.save, recovery can remove that copy.
            val journal = File(directory, "share-$COPY_SESSION-$id$JOURNAL_SUFFIX")
            captured.add(journal)
            FileOutputStream(journal).use { output ->
                output.write(file.name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            val temporary = File.createTempFile("share-$COPY_SESSION-", ".part", directory)
            captured.add(temporary)
            val input = contentResolver.openInputStream(source) ?: error("Shared image unavailable")
            input.use { stream ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SHARED_IMAGE_BYTES) { "Shared image too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temporary.renameTo(file)) { "Shared image copy failed" }
            captured.add(file)
            FileProvider.getUriForFile(this, "$packageName.provider", file)
        }
    } catch (_: Exception) {
        captured.forEach(File::delete)
        null
    }
}

/** Delete only this app's opaque composer copies; never follow an external URI or path. */
internal fun Context.discardSharedComposerImages(uris: List<Uri>) {
    val directory = File(filesDir, "composer-shares")
    uris.forEach { uri -> ownedComposerName(uri)?.let { name ->
        File(directory, name).delete()
        directory.listFiles().orEmpty().filter { file ->
            file.name.startsWith("share-") && file.name.endsWith("-${name.substringBeforeLast('.')}$JOURNAL_SUFFIX")
        }.forEach(File::delete)
    } }
}

/** The composer is durable before its per-copy recovery marker can be removed. */
internal fun Context.settleSharedComposerImages(uris: List<Uri>) {
    val directory = File(filesDir, "composer-shares")
    uris.forEach { uri -> ownedComposerName(uri)?.let { name ->
        File(directory, "share-$COPY_SESSION-${name.substringBeforeLast('.')}$JOURNAL_SUFFIX").delete()
    } }
}

/** Only a prior process's journal can be recovered; this process may still be copying. */
internal fun Context.recoverSharedComposerImages(referenced: Set<String>, activeSession: String = COPY_SESSION) {
    val directory = File(filesDir, "composer-shares")
    directory.listFiles().orEmpty().filter { file ->
        file.name.startsWith("share-") && file.name.endsWith(".part") &&
            !file.name.startsWith("share-$activeSession-")
    }.forEach(File::delete)
    directory.listFiles().orEmpty().filter { file ->
        file.name.startsWith("share-") && file.name.endsWith(JOURNAL_SUFFIX) &&
            !file.name.startsWith("share-$activeSession-")
    }.forEach { journal ->
        val name = runCatching { journal.readText(Charsets.UTF_8) }.getOrNull()
        if (name != null && OWNED_NAME.matches(name)) {
            if (name !in referenced) File(directory, name).delete()
            journal.delete()
        }
    }
}

internal fun Context.ownedSharedComposerName(uri: Uri): String? = ownedComposerName(uri)

internal fun Context.composerAttachmentName(uri: Uri): String =
    if (ownedComposerName(uri) != null) "Shared image"
    else uri.lastPathSegment.orEmpty()

private fun Context.ownedComposerName(uri: Uri): String? {
    if (uri.scheme != "content" || uri.authority != "$packageName.provider" || uri.pathSegments.size != 2) return null
    if (uri.pathSegments[0] != OWNED_PATH) return null
    return uri.pathSegments[1].takeIf(OWNED_NAME::matches)
}
