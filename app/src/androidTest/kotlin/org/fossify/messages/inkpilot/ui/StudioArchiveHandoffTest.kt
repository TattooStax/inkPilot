package org.fossify.messages.inkpilot.ui

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioArchiveHandoffTest {
    private val document = Uri.parse("content://synthetic.archive/document/1")

    @Before fun emulatorOnly() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only." }
    }

    @Test fun lockedPickerResultDeletesDocumentWithoutStartingExport() {
        var deleted: Uri? = null
        var started = false
        val result = StudioArchiveHandoff.handle(document, locked = true,
            discard = { deleted = it; true }, start = { started = true })
        assertEquals(StudioArchiveHandoff.Outcome.LOCKED_DISCARDED, result)
        assertEquals(document, deleted)
        assertFalse(started)
    }

    @Test fun undeletableLockedResultStillNeverStartsExport() {
        var started = false
        val result = StudioArchiveHandoff.handle(document, locked = true,
            discard = { throw SecurityException("Synthetic document provider refusal") },
            start = { started = true })
        assertEquals(StudioArchiveHandoff.Outcome.LOCKED_UNDELETED, result)
        assertFalse(started)
    }

    @Test fun unlockedResultStartsOnlySelectedDocument() {
        var started: Uri? = null
        val result = StudioArchiveHandoff.handle(document, locked = false,
            discard = { error("The unlocked document must not be discarded.") },
            start = { started = it })
        assertEquals(StudioArchiveHandoff.Outcome.STARTED, result)
        assertEquals(document, started)
    }
}
