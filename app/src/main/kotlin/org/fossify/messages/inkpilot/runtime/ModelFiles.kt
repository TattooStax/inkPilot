package org.fossify.messages.inkpilot.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.fossify.messages.inkpilot.data.StudioRepository
import java.io.File
import java.io.FileOutputStream

internal object ModelFiles {
    private const val MAXIMUM_BYTES = 2_000_000_000L

    fun active(context: Context) = File(context.filesDir, "models/active.gguf")

    /** A private, atomic replacement: an interrupted import never replaces working weights. */
    fun import(context: Context, uri: Uri): String = synchronized(AgentRuntime.inferenceLock) {
        val target = active(context)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create model storage." }
        val temporary = File.createTempFile("import-", ".gguf", target.parentFile)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read this model file." }
                FileOutputStream(temporary).use { output ->
                    val magic = ByteArray(4)
                    var filled = 0
                    while (filled < magic.size) {
                        val read = input.read(magic, filled, magic.size - filled)
                        require(read > 0) { "This file is not a GGUF model." }
                        filled += read
                    }
                    require(magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "Choose a GGUF model file." }
                    output.write(magic)
                    var total = 4L
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAXIMUM_BYTES) { "Choose a text model smaller than 2 GB." }
                        output.write(buffer, 0, read)
                    }
                    require(total >= 1024 * 1024) { "This model file is incomplete." }
                    output.fd.sync()
                }
            }
            StudioRepository(context).use { it.invalidateAllPending() }
            check(temporary.renameTo(target)) { "Cannot replace the local model." }
            val name = displayName(context, uri)
            AgentSettings(context).apply {
                recordModel(name)
                recordStatus("Model imported. Enable drafts when you are ready.")
            }
            name
        } finally {
            temporary.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).take(100) else null
        }
    }.getOrNull() ?: "Local GGUF model"
}
