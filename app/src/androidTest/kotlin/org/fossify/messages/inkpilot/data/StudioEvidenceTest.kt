package org.fossify.messages.inkpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioEvidenceTest : StudioDatabaseTestBase() {
    @Test fun oneSnapshotContainsOnlyReviewedContactMemoryAtCurrentRevision() {
        val original = inbound()
        repository.saveMemory(original.contactId, "Traditional rose, upper arm.")
        val current = original.copy(expectedRevision = repository.contact(original.contactId)!!.revision)
        val packet = requireNotNull(repository.readEvidence(current))
        assertEquals(current, packet.request)
        assertEquals(current.expectedRevision, packet.source.revision)
        assertEquals("Traditional rose, upper arm.", packet.reviewedMemory.single().text)
        assertTrue(packet.verifiedFacts.isEmpty())

        repository.setSoul("Staff style note only.")
        val changed = requireNotNull(repository.readEvidence(current))
        assertTrue(changed.source.revision > packet.source.revision)
        assertEquals("Staff style note only.", changed.studioGuide)
        assertNull(repository.readEvidence(current.copy(contactId = "another-contact")))
    }
}
