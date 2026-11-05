package org.fossify.messages.inkpilot.data

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.ui.StudioArchiveHandoff
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioArchiveCleanupNoticeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "synthetic-studio-cleanup-" + UUID.randomUUID()
    private val document = Uri.parse("content://synthetic.archive/document/locked")

    @Before fun emulatorOnly() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only." }
    }

    @After fun removeOwnedPreferences() {
        context.deleteSharedPreferences(name)
    }

    @Test fun lockedProviderRefusalPersistsUntilUnlockedAcknowledgementAfterReopen() {
        val notice = StudioArchiveCleanupNotice(context, name)
        var started = false
        val outcome = StudioArchiveHandoff.handle(document, locked = true,
            discard = { notice.recordIfDeletionFails { false } },
            start = { started = true })
        assertEquals(StudioArchiveHandoff.Outcome.LOCKED_UNDELETED, outcome)
        assertFalse(started)
        val reopened = StudioArchiveCleanupNotice(context, name)
        assertNull(reopened.pendingGenerationIfUnlocked(false))
        val generation = requireNotNull(reopened.pendingGenerationIfUnlocked(true))
        assertFalse(reopened.acknowledgeIfUnlocked(false, generation))
        assertEquals(generation, StudioArchiveCleanupNotice(context, name).pendingGenerationIfUnlocked(true))
        assertTrue(reopened.acknowledgeIfUnlocked(true, generation))
        assertNull(reopened.pendingGenerationIfUnlocked(true))
    }

    @Test fun newerFailureSurvivesOlderDialogAcknowledgementWithoutStoringUri() {
        val notice = StudioArchiveCleanupNotice(context, name)
        assertFalse(notice.recordIfDeletionFails { throw SecurityException("Synthetic refusal") })
        val older = requireNotNull(notice.pendingGenerationIfUnlocked(true))
        assertFalse(notice.recordIfDeletionFails { false })
        val newer = requireNotNull(StudioArchiveCleanupNotice(context, name).pendingGenerationIfUnlocked(true))
        assertTrue(newer > older)
        assertFalse(notice.acknowledgeIfUnlocked(true, older))
        assertEquals(newer, notice.pendingGenerationIfUnlocked(true))
        val entries = context.getSharedPreferences(name, 0).all
        assertFalse(entries.values.any { it.toString().contains("synthetic.archive") })
        assertTrue(notice.acknowledgeIfUnlocked(true, newer))
    }

    @Test fun deletedDocumentDoesNotCreateWarning() {
        val notice = StudioArchiveCleanupNotice(context, name)
        assertTrue(notice.recordIfDeletionFails { true })
        assertNull(StudioArchiveCleanupNotice(context, name).pendingGenerationIfUnlocked(true))
    }
}
