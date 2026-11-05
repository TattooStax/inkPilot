package org.fossify.messages.inkpilot.smoke

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.HeldDraftWrite
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.ui.ExternalRouteParser
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.encode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the actual async selected-draft route, including its production footer. */
@RunWith(AndroidJUnit4::class)
class FocusedReviewRouteTest {
    @Test fun selectedHeldReplyAndSimAreVisibleBeforeAnyRegularComposer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val event = "ui:$token"
        val thread = 900_000_000L + (UUID.randomUUID().mostSignificantBits and 0x0fffffffL)
        val body = "Thanks for the reference. What size and placement are you considering?"
        val repository = StudioRepository(context)
        val wasEnabled = repository.isDraftingEnabled()
        var draftId: String? = null
        var contactId: String? = null
        try {
            repository.setDraftingEnabled(true)
            val request = repository.recordInbound(event, thread, "+1555${token.filter(Char::isDigit).padEnd(7, '0').take(7)}",
                "Synthetic studio question", "UI fixture", System.currentTimeMillis(), 1)
            contactId = request.contactId
            val recipient = requireNotNull(repository.readSource(event)).recipient
            assertTrue(repository.holdIfCurrent(HeldDraftWrite(DraftKey(event), request.threadId,
                request.contactId, recipient, request.expectedRevision, body)) is DraftStoreResult.Held)
            val selectedId = repository.heldDrafts().single { it.eventId == event }.draftId
            draftId = selectedId
            val route = StudioRoute.Draft(selectedId)
            ActivityScenario.launch<StudioHomeActivity>(Intent(context, StudioHomeActivity::class.java)
                .putExtra(ExternalRouteParser.ROUTE, route.encode())).use {
                SmokeViews.assertText(body)
                SmokeViews.assertText("Choose business SIM")
                it.onActivity { activity ->
                    fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                        (0 until view.childCount).flatMap { index -> descendants(view.getChildAt(index)) } else emptyList()
                    val labels = descendants(activity.findViewById(org.fossify.messages.R.id.inkpilot_canvas))
                        .filterIsInstance<TextView>().map { view -> view.text.toString() }
                    assertFalse("Focused review must not render the regular composer",
                        "Attach image (up to 1 MiB)" in labels)
                }
            }
        } finally {
            val db = repository.database
            draftId?.let { db.delete("held_drafts", "draft_id = ?", arrayOf(it)) }
            db.delete("inbound_events", "event_id = ?", arrayOf(event))
            contactId?.let { db.delete("studio_contacts", "id = ?", arrayOf(it)) }
            repository.setDraftingEnabled(wasEnabled)
            repository.close()
        }
    }
}
