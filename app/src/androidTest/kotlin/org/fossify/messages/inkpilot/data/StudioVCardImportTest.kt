package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.inkpilot.ui.VCardPreviewResult
import org.fossify.messages.inkpilot.ui.parseVCardPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioVCardImportTest : StudioDatabaseTestBase() {
    @Test fun boundedCardPreviewAndExplicitNewContactDoNotRenameAnExistingClient() {
        val bytes = """BEGIN:VCARD
            |VERSION:3.0
            |FN:Maya Ortiz
            |TEL;TYPE=CELL:+1 (202) 555-0142
            |END:VCARD
        """.trimMargin().toByteArray()
        val preview = parseVCardPreview(bytes) as VCardPreviewResult.Ready
        assertEquals("Maya Ortiz", preview.cards.single().name)
        val number = preview.cards.single().phones.single()
        assertEquals("+12025550142", number)
        val added = repository.importVCardContact(number, preview.cards.single().name) as VCardImportResult.Added
        assertEquals("Maya Ortiz", added.contact.displayName)
        assertEquals(VCardImportResult.NeedsReview, repository.importVCardContact(number, "Different card name"))
        assertEquals(VCardImportResult.NeedsReview, repository.importVCardContact("2025550142", "Country-code variant"))
        assertEquals("Maya Ortiz", repository.contact(added.contact.id)?.displayName)
        assertEquals(1, repository.contacts().size)
    }

    @Test fun oversizedOrUnusableCardCannotBecomeAnImportChoice() {
        assertEquals(VCardPreviewResult.Unavailable, parseVCardPreview(ByteArray(256 * 1024 + 1)))
        val card = "BEGIN:VCARD\nVERSION:3.0\nFN:No phone\nTEL:call-me\nEND:VCARD".toByteArray()
        val preview = parseVCardPreview(card) as VCardPreviewResult.Ready
        assertTrue(preview.cards.single().phones.isEmpty())
        val uriCard = "BEGIN:VCARD\nVERSION:4.0\nFN:URI phone\nTEL;VALUE=uri:tel:+12025550143\nEND:VCARD"
        val uriPreview = parseVCardPreview(uriCard.toByteArray()) as VCardPreviewResult.Ready
        assertEquals("+12025550143", uriPreview.cards.single().phones.single())
        val extension = "BEGIN:VCARD\nVERSION:4.0\nFN:Extension\nTEL;VALUE=uri:tel:+12025550143;ext=9\nEND:VCARD"
        val extensionPreview = parseVCardPreview(extension.toByteArray()) as VCardPreviewResult.Ready
        assertTrue(extensionPreview.cards.single().phones.isEmpty())
    }

    @Test fun anAliasInAnotherRegionBlocksEitherNanpFormWithoutRenamingItsClient() {
        val existing = repository.upsertContact("+14165550100", "Existing client")
        repository.reviewAlias("+12025550142", "CA", existing.id)
        repository.reviewAlias("2025550143", "CA", existing.id)

        assertEquals(VCardImportResult.NeedsReview, repository.importVCardContact("2025550142", "Shared card"))
        assertEquals(VCardImportResult.NeedsReview, repository.importVCardContact("+12025550143", "Other card"))
        assertEquals("Existing client", repository.contact(existing.id)?.displayName)
        assertEquals(1, repository.contacts().size)
    }

    @Test fun cardAndPhoneBudgetsFailClosed() {
        val nineCards = (1..9).joinToString("\n") { index ->
            "BEGIN:VCARD\nVERSION:3.0\nFN:Client $index\nTEL:+12025550142\nEND:VCARD"
        }
        assertEquals(VCardPreviewResult.Unavailable, parseVCardPreview(nineCards.toByteArray()))
        val manyNumbers = (100..116).joinToString("\n") { "TEL:+12025550$it" }
        val card = "BEGIN:VCARD\nVERSION:3.0\nFN:Client\n$manyNumbers\nEND:VCARD"
        assertEquals(VCardPreviewResult.Unavailable, parseVCardPreview(card.toByteArray()))
    }
}
