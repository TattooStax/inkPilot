package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftStoreResult
import org.fossify.messages.inkpilot.data.DraftRegenerationOutcome
import org.fossify.messages.inkpilot.data.StudioDatabaseTestBase
import org.fossify.messages.inkpilot.data.markStaffAttentionHandled
import org.fossify.messages.inkpilot.data.staffAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class StudioStaffAttentionTest : StudioDatabaseTestBase() {
    @Test fun failedTasksSurviveGlobalRetryAndHandlingClosesOlderTasksWithoutHidingNewerArrivals() {
        val older = inbound("sms:z-older")
        assertTrue(repository.recordDraftAttempt(older.eventId, older.expectedRevision, "UNSAFE_CONTENT"))
        val newer = inbound("sms:a-newer") // Same timestamp, reverse lexical event IDs.
        assertTrue(repository.recordDraftAttempt(newer.eventId, newer.expectedRevision, "REPLY_LIMIT"))
        assertEquals(newer.eventId, repository.staffAttention().single().eventId)
        reopen()
        repository.clearDraftAttempts()
        assertEquals(newer.eventId, repository.staffAttention().single().eventId)
        assertEquals(newer, repository.pendingRequests().single())

        assertTrue(repository.markStaffAttentionHandled(newer.eventId, newer.expectedRevision))
        assertTrue(repository.staffAttention().isEmpty())
        assertFalse(repository.markStaffAttentionHandled(newer.eventId, newer.expectedRevision))
        assertTrue(repository.pendingRequests().isEmpty())
        assertFalse(repository.recordDraftAttempt(newer.eventId, newer.expectedRevision, "Late failure"))
        assertEquals(DraftStoreResult.SourceChanged, repository.holdIfCurrent(write(newer)))
        repository.saveMemory(newer.contactId, "Staff handled this conversation")
        assertTrue(repository.pendingRequests().isEmpty())
        val revised = checkNotNull(repository.readSource(newer.eventId)).revision
        assertFalse(repository.recordDraftAttempt(newer.eventId, revised, "Late revised failure"))
        assertEquals(DraftRegenerationOutcome.Stale, repository.regenerate(newer.eventId, revised))
        repository.database.rawQuery("SELECT COUNT(*) FROM staff_attention WHERE resolution = 'STAFF_HANDLED'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
        }
        val latest = inbound("sms:next", receivedAt = 2_000)
        assertEquals(latest, repository.pendingRequests().single())
        assertTrue(repository.recordDraftAttempt(latest.eventId, latest.expectedRevision, "Model or source read failed"))
        assertEquals(latest.eventId, repository.staffAttention().single().eventId)
    }

    @Test fun successfulHeldReplacementResolvesTaskWithoutClearingHistoricalReason() {
        val request = inbound()
        assertTrue(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "UNSAFE_CONTENT"))
        repository.clearDraftAttempts()
        assertTrue(repository.holdIfCurrent(write(request)) is DraftStoreResult.Held)
        reopen()
        assertTrue(repository.staffAttention().isEmpty())
        assertEquals(1, repository.heldDrafts().size)
        repository.database.rawQuery("SELECT reason, resolution FROM staff_attention WHERE event_id = ?",
            arrayOf(request.eventId)).use {
            assertTrue(it.moveToFirst())
            assertEquals("UNSAFE_CONTENT", it.getString(0))
            assertEquals("REPLACED", it.getString(1))
        }
    }

    @Test fun regenerationFailureKeepsAttentionUntilActualReplacementOrStaffHandling() {
        val request = inbound()
        assertTrue(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "CONTEXT_LIMIT"))
        assertTrue(repository.regenerate(request.eventId, request.expectedRevision) is DraftRegenerationOutcome.Held)
        assertEquals(request, repository.pendingRequests().single())
        assertEquals(request.eventId, repository.staffAttention().single().eventId)
        // A failed worker can record another attempt without creating a duplicate staff task.
        assertTrue(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "MODEL_FAILED"))
        assertTrue(repository.heldDrafts().isEmpty())
        reopen()
        assertEquals("CONTEXT_LIMIT", repository.staffAttention().single().reason)
        assertTrue(repository.pendingRequests().isEmpty())
    }

    @Test fun replacementAtNewRevisionClosesOldTaskButKeepsLaterArrival() {
        val request = inbound("sms:older")
        assertTrue(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "UNSAFE_CONTENT"))
        repository.saveMemory(request.contactId, "Reviewed appointment rules")
        val revised = checkNotNull(repository.readSource(request.eventId))
        val replacement = DraftRequest(request.eventId, request.threadId, request.contactId, revised.revision)
        val later = inbound("sms:later", number = "+15559876543", receivedAt = 2_000)
        assertTrue(repository.recordDraftAttempt(later.eventId, later.expectedRevision, "MODEL_FAILED"))
        assertTrue(repository.holdIfCurrent(write(replacement)) is DraftStoreResult.Held)
        assertEquals(later.eventId, repository.staffAttention().single().eventId)
        repository.database.rawQuery("SELECT resolution FROM staff_attention WHERE event_id = ?",
            arrayOf(request.eventId)).use {
            assertTrue(it.moveToFirst()); assertEquals("REPLACED", it.getString(0))
        }
        reopen()
        assertEquals(later.eventId, repository.staffAttention().single().eventId)
    }

    @Test fun safeHeldDraftForAnotherInboundEventCannotEraseBlockedQuestion() {
        val blocked = inbound("sms:blocked")
        assertTrue(repository.recordDraftAttempt(blocked.eventId, blocked.expectedRevision, "UNSAFE_CONTENT"))
        val followup = inbound("sms:followup", receivedAt = 2_000)
        assertTrue(repository.holdIfCurrent(write(followup)) is DraftStoreResult.Held)
        assertEquals(blocked.eventId, repository.staffAttention().single().eventId)
        reopen()
        assertEquals(blocked.eventId, repository.staffAttention().single().eventId)
    }

    @Test fun handlingStaleTaskDoesNotCloseNewerAlreadyWaitingTask() {
        val older = inbound("sms:z-older")
        assertTrue(repository.recordDraftAttempt(older.eventId, older.expectedRevision, "CONTEXT_LIMIT"))
        val newer = inbound("sms:a-newer")
        assertTrue(repository.recordDraftAttempt(newer.eventId, newer.expectedRevision, "MODEL_FAILED"))
        assertTrue(repository.markStaffAttentionHandled(older.eventId, older.expectedRevision))
        assertEquals(newer.eventId, repository.staffAttention().single().eventId)
    }

    @Test fun versionSixMigrationBackfillsOnlyUnansweredAttempts() {
        val active = inbound("sms:active")
        assertTrue(repository.recordDraftAttempt(active.eventId, active.expectedRevision, "CONTEXT_LIMIT"))
        val heldEvent = inbound("sms:held", number = "+15551234568", receivedAt = 2_000)
        assertTrue(repository.recordDraftAttempt(heldEvent.eventId, heldEvent.expectedRevision, "MODEL_FAILED"))
        repository.saveMemory(heldEvent.contactId, "Reviewed")
        val heldRevision = checkNotNull(repository.readSource(heldEvent.eventId)).revision
        val revisedHeld = DraftRequest(heldEvent.eventId, heldEvent.threadId, heldEvent.contactId, heldRevision)
        assertTrue(repository.holdIfCurrent(write(revisedHeld)) is DraftStoreResult.Held)
        val claimedEvent = inbound("sms:claimed", number = "+15551234569", receivedAt = 3_000)
        assertTrue(repository.recordDraftAttempt(claimedEvent.eventId, claimedEvent.expectedRevision, "UNSAFE_CONTENT"))
        repository.saveMemory(claimedEvent.contactId, "Reviewed")
        val claimedRevision = checkNotNull(repository.readSource(claimedEvent.eventId)).revision
        val revisedClaimed = DraftRequest(claimedEvent.eventId, claimedEvent.threadId, claimedEvent.contactId,
            claimedRevision)
        assertTrue(repository.holdIfCurrent(write(revisedClaimed)) is DraftStoreResult.Held)
        val heldDraft = repository.heldDrafts().single { it.eventId == claimedEvent.eventId }
        checkNotNull(repository.claimDraftForSend(heldDraft.draftId, heldDraft.body, heldDraft.body, 3))
        repository.close()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use {
            it.execSQL("DROP TABLE staff_attention")
            it.version = 6
        }
        reopen()
        assertEquals(11, repository.database.version)
        assertEquals(active.eventId, repository.staffAttention().single().eventId)
        assertTrue(repository.pendingRequests().isEmpty())
        repository.database.rawQuery("SELECT COUNT(*) FROM staff_attention", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        repository.database.rawQuery("PRAGMA foreign_key_check", null).use {
            assertFalse(it.moveToFirst())
        }
    }

    @Test fun draftSideCardRendersBlockedTaskRoutesInShellAndMarksHandled() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val request = inbound()
        assertTrue(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "UNSAFE_CONTENT"))
        reopen()
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var opened: StudioRoute? = null
                var refreshes = 0
                val panel = DraftPanel(activity, repository, StudioWork(activity, Executor { it.run() }),
                    refresh = { refreshes++ }, open = { opened = it })
                val screen = panel.view()
                activity.setContentView(screen)
                val labels = labels(screen)
                assertTrue(labels.contains("Staff reply needed"))
                assertTrue(labels.any { it.contains("unverified or unsupported reply") })
                assertTrue(labels.any { it.contains("Client: Can I book a consultation?") })
                findText(screen, "Open conversation").performClick()
                assertEquals(StudioRoute.Thread(42), opened)
                findText(screen, "Mark handled").performClick()
                assertEquals(1, refreshes)
                assertTrue(repository.staffAttention().isEmpty())
            }
        }
    }

    private fun labels(root: View): List<String> = buildList {
        fun visit(view: View) {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    private fun findText(root: View, value: String): TextView {
        if (root is TextView && root.text.toString() == value) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            val child = runCatching { findText(root.getChildAt(index), value) }.getOrNull()
            if (child != null) return child
        }
        error("Missing action: $value")
    }
}
