package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal const val JOURNAL_SUFFIX = ".journal"
internal val OWNED_NAME = Regex("[0-9a-f-]{36}\\.(?:png|jpg)")
private const val DISCARD_PREFIX = "discard-"
private const val TERMINAL_DISCARD_PREFIX = "discard-terminal-"

/** Delete only this app's opaque composer copies; never follow an external URI or path. */
internal fun Context.discardSharedComposerImages(uris: List<Uri>) {
    val directory = File(filesDir, "composer-shares")
    uris.mapNotNull(::ownedComposerName).forEach { directory.discardOwnedComposerImage(it) }
}

private fun File.discardOwnedComposerImage(name: String) {
    val copy = File(this, name)
    if (copy.exists() && !copy.delete()) return
    listFiles().orEmpty().filter { file ->
        file.name.startsWith("share-") && file.name.endsWith("-${name.substringBeforeLast('.')}$JOURNAL_SUFFIX") ||
            file.name.startsWith(DISCARD_PREFIX) && file.name.endsWith(JOURNAL_SUFFIX) &&
            runCatching { file.readText(Charsets.UTF_8) == name }.getOrDefault(false)
    }.forEach(File::delete)
}

/** Commit an intent to release each owned copy before removing its saved URI. */
internal fun Context.markSharedComposerImagesForDiscard(uris: List<Uri>, terminal: Boolean = false): Boolean {
    val directory = File(filesDir, "composer-shares").apply { mkdirs() }
    return uris.mapNotNull(::ownedComposerName).all { name ->
        runCatching {
            val prefix = if (terminal) TERMINAL_DISCARD_PREFIX else DISCARD_PREFIX
            val marker = File(directory, "$prefix${UUID.randomUUID()}$JOURNAL_SUFFIX")
            FileOutputStream(marker).use { output ->
                output.write(name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }.isSuccess
    }
}

/** Conditional drafts preserve live references; terminal accepted sends release their copy. */
internal fun File.recoverDiscardSharedComposerImages(referenced: Set<String>) {
    listFiles().orEmpty().filter { file ->
        file.name.startsWith(DISCARD_PREFIX) && file.name.endsWith(JOURNAL_SUFFIX)
    }.forEach { journal ->
        val name = runCatching { journal.readText(Charsets.UTF_8) }.getOrNull()
        when {
            name == null || !OWNED_NAME.matches(name) ||
                name in referenced && !journal.name.startsWith(TERMINAL_DISCARD_PREFIX) -> journal.delete()
            else -> discardOwnedComposerImage(name)
        }
    }
}
