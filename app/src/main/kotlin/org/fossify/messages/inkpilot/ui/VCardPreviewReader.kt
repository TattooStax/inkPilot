package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.net.Uri
import ezvcard.Ezvcard
import org.fossify.messages.helpers.parseNameFromVCard
import org.fossify.messages.inkpilot.data.canonicalStudioNumber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val MAX_CARD_BYTES = 256 * 1024
private const val MAX_CARDS = 8
private const val MAX_PHONE_CHOICES = 16
private const val READ_BUFFER_BYTES = 8_192
private const val MAX_CONTACT_NAME_LENGTH = 256

internal data class VCardContactPreview(val name: String, val phones: List<String>)
internal sealed interface VCardPreviewResult {
    data class Ready(val cards: List<VCardContactPreview>) : VCardPreviewResult
    data object Unavailable : VCardPreviewResult
}

/** Untrusted contact cards are read with a fixed byte and row budget before parsing. */
internal fun readVCardPreview(context: Context, uri: Uri): VCardPreviewResult = runCatching {
    if (uri.scheme != "content") return@runCatching VCardPreviewResult.Unavailable
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) return@runCatching VCardPreviewResult.Unavailable
            if (output.size() + count > MAX_CARD_BYTES) return@runCatching VCardPreviewResult.Unavailable
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: return@runCatching VCardPreviewResult.Unavailable
    parseVCardPreview(bytes)
}.getOrDefault(VCardPreviewResult.Unavailable)

internal fun parseVCardPreview(bytes: ByteArray): VCardPreviewResult = runCatching {
    if (bytes.isEmpty() || bytes.size > MAX_CARD_BYTES) return@runCatching VCardPreviewResult.Unavailable
    val cards = Ezvcard.parse(ByteArrayInputStream(bytes)).all()
    if (cards.isEmpty() || cards.size > MAX_CARDS) return@runCatching VCardPreviewResult.Unavailable
    val previews = cards.map { card ->
        val numbers = card.telephoneNumbers.mapNotNull { telephone ->
            val telUri = telephone.uri
            if (telUri?.extension != null || telUri?.phoneContext != null || telUri?.isdnSubaddress != null)
                return@mapNotNull null
            val number = telephone.text ?: telUri?.number ?: return@mapNotNull null
            runCatching { canonicalStudioNumber(number) }.getOrNull()
        }.distinct()
        VCardContactPreview(card.parseNameFromVCard().orEmpty().trim().take(MAX_CONTACT_NAME_LENGTH), numbers)
    }
    if (previews.sumOf { it.phones.size } > MAX_PHONE_CHOICES) VCardPreviewResult.Unavailable
    else VCardPreviewResult.Ready(previews)
}.getOrDefault(VCardPreviewResult.Unavailable)
