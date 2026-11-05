package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val MAX_IMAGE_BYTES = 1_048_576
private const val READ_BUFFER_BYTES = 8_192
private val IMAGE_TYPES = setOf("image/jpeg", "image/png")

/** Validate before claiming a send. The transport repeats the size check at handoff. */
internal fun Context.inShellMmsRejection(uris: List<Uri>): String? {
    if (uris.size > 1) return "Send one image per message"
    val uri = uris.singleOrNull() ?: return null
    if (contentResolver.getType(uri) !in IMAGE_TYPES) return "Attach a JPEG or PNG image"
    return try {
        contentResolver.openInputStream(uri)?.use { it.sizeRejection() } ?: "Attachment is unreadable"
    } catch (_: Exception) { "Attachment is unreadable" }
}

private fun InputStream.sizeRejection(): String? {
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return null
        total += count
        if (total > MAX_IMAGE_BYTES) return "Image exceeds the 1 MiB MMS limit"
    }
}

/** A changed provider URI must not bypass the preflight's bounded read. */
internal fun InputStream.readBoundedMmsImage(mimeType: String): ByteArray {
    require(mimeType in IMAGE_TYPES) { "Unsupported MMS image type" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= MAX_IMAGE_BYTES) { "MMS image exceeds limit" }
        output.write(buffer, 0, count)
    }
}
