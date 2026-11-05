package org.fossify.messages.inkpilot.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.inkpilot.data.StudioDatabaseTestBase
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
    @Test fun blockedAttemptSurvivesReopenAndRemainsVisibleAfterContextChanges() {
        val first = inbound()
        assertTrue(repository.recordDraftAttempt(first.eventId, first.expectedRevision, "UNSAFE_CONTENT"))
        assertTrue(repository.pendingRequests().isEmpty())
        assertTrue(repository.heldDrafts().isEmpty())
        reopen()
        val original = repository.staffAttention().single()
        assertEquals(first.eventId, original.eventId)
        assertEquals(42L, original.threadId)
        assertEquals("UNSAFE_CONTENT", original.reason)
        assertTrue(original.current)

        repository.saveMemory(first.contactId, "Staff updated the studio facts")
        assertFalse(repository.staffAttention().single().current)
        val latest = inbound("sms:2", receivedAt = 2_000)
        assertTrue(repository.recordDraftAttempt(latest.eventId, latest.expectedRevision, "REPLY_LIMIT"))
        assertEquals(latest.eventId, repository.staffAttention().single().eventId)

        val other = repository.recordInbound("sms:3", 43, "+15559876543", "Can we talk?", "Another client", 3_000, 3)
        assertTrue(repository.recordDraftAttempt(other.eventId, other.expectedRevision, "Model or source read failed"))
        reopen()
        assertEquals(setOf(42L, 43L), repository.staffAttention().map { it.threadId }.toSet())
        repository.clearDraftAttempts()
        assertTrue(repository.staffAttention().isEmpty())
    }

    @Test fun draftSideCardRendersBlockedTaskAndOpensItsInShellThread() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val request = inbound()
        assertTrue(repository.recordDraftAttempt(request.eventId, request.expectedRevision, "UNSAFE_CONTENT"))
        reopen()
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var opened: StudioRoute? = null
                val panel = DraftPanel(activity, repository, StudioWork(activity, Executor { it.run() }),
                    refresh = {}, open = { opened = it })
                val screen = panel.view()
                activity.setContentView(screen)
                val labels = labels(screen)
                assertTrue(labels.contains("Staff reply needed"))
                assertTrue(labels.any { it.contains("unverified booking claim") })
                assertTrue(labels.any { it.contains("Client: Can I book a consultation?") })
                findText(screen, "Open conversation").performClick()
                assertEquals(StudioRoute.Thread(42), opened)
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
