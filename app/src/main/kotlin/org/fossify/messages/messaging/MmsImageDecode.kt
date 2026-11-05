package org.fossify.messages.messaging

import android.graphics.BitmapFactory

private const val MAX_DECODE_PIXELS = 1_048_576L

/** Bound decoded memory and require the image bytes to match their declared type. */
internal fun ByteArray.isDecodableMmsImage(mimeType: String): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val validHeader = runCatching {
        BitmapFactory.decodeByteArray(this, 0, size, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == mimeType
    }.getOrDefault(false)
    if (!validHeader) return false
    var sample = 1
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_DECODE_PIXELS &&
        sample < (1 shl 30)) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeByteArray(this, 0, size, options)?.let { bitmap ->
            bitmap.recycle()
            true
        } ?: false
    }.getOrDefault(false)
}
