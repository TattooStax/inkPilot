package org.fossify.messages.inkpilot.smoke

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.ui.*
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.inkpilot.data.ReviewDraft
import org.fossify.messages.models.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class ThreadReviewRegressionTest {
    @Test fun providerFailureCanRetryTheSameThreadPage() {
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var attempts = 0
                val source = object : StudioConversations {
                    override fun pageThreads(after: ThreadCursor?, limit: Int) = Page<Conversation, ThreadCursor>(emptyList(), null)
                    override fun pageMessages(threadId: Long, after: MessageCursor?, limit: Int): Page<Message, MessageCursor> {
                        assertNull("failed page must not advance cursor", after)
                        if (++attempts == 1) error("Provider temporarily unavailable")
                        return Page(listOf(message(7)), null)
                    }
                    override fun markRead(threadId: Long) = Unit
                }
                val panel = MessagePanel(activity, StudioWork(activity, Executor { it.run() }), {}, {}, gateway = source).thread(42)
                val list = children(panel).filterIsInstance<RecyclerView>().single()
                assertEquals(0, list.adapter!!.itemCount)
                children(panel).filterIsInstance<TextView>().single { it.text.toString() == "Load older messages" }.performClick()
                assertEquals(2, attempts)
                assertEquals(1, list.adapter!!.itemCount)
            }
        }
    }

    @Test fun providerFailureCanRetryTheSameInboxPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var attempts = 0
                val source = object : StudioConversations {
                    override fun pageThreads(after: ThreadCursor?, limit: Int): Page<Conversation, ThreadCursor> {
                        assertNull(after)
                        if (++attempts == 1) error("Provider temporarily unavailable")
                        return Page(listOf(Conversation(42, "fixture", 100, true, "Fixture", "", false, "+15551234567")), null)
                    }
                    override fun pageMessages(threadId: Long, after: MessageCursor?, limit: Int) = Page<Message, MessageCursor>(emptyList(), null)
                    override fun markRead(threadId: Long) = Unit
                }
                val panel = MessagePanel(activity, StudioWork(activity, Executor { it.run() }), {}, {}, gateway = source).messages()
                children(panel).filterIsInstance<TextView>().single { it.text.toString() == "Load more conversations" }.performClick()
                assertEquals(2, attempts)
                assertEquals(1, children(panel).filterIsInstance<RecyclerView>().single().adapter!!.itemCount)
            }
        }
    }

    @Test fun olderTypedAnchorAndOffsetSurviveInsertionAndTwoRotations() {
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val route = StudioRoute.Thread(42)
                fun list(rows: List<Message>) = StudioPagedList(activity).apply {
                    layoutManager = LinearLayoutManager(activity).apply { reverseLayout = true }
                    adapter = StudioThreadAdapter(activity).apply { append(rows) }
                }
                val original = StudioCanvasState()
                val rows = (100L downTo 1L).map { message(it) }
                val first = original.view(route) { list(rows) } as StudioPagedList
                first.contentReady(100, false)
                val manager = first.layoutManager as LinearLayoutManager
                manager.scrollToPositionWithOffset(65, 17)
                layout(first)
                val position = manager.findFirstVisibleItemPosition()
                val key = (first.adapter as StudioMessageRows).messageKey(position)!!
                val offset = first.height - first.paddingBottom - manager.getDecoratedBottom(manager.findViewByPosition(position)!!)
                // Save an anchor beyond page one, insert rows ahead of it, rotate before its page arrives.
                val changed = listOf(message(key.id, true), message(999)) + rows
                val recreated = StudioCanvasState(original.save())
                val partial = recreated.view(route) { list(changed.take(40)) } as StudioPagedList
                partial.contentReady(40, true)
                val again = StudioCanvasState(recreated.save())
                val restored = again.view(route) { list(changed) } as StudioPagedList
                restored.contentReady(changed.size, false)
                layout(restored)
                val restoredManager = restored.layoutManager as LinearLayoutManager
                val anchoredPosition = (restored.adapter as StudioMessageRows).messagePosition(key)
                val anchored = restoredManager.findViewByPosition(anchoredPosition)
                assertNotNull("same typed provider row remains visible", anchored)
                assertEquals(offset, restored.height - restored.paddingBottom - restoredManager.getDecoratedBottom(anchored!!))
            }
        }
    }

    @Test fun hiddenThreadsAreInvalidatedWithoutDroppingOtherPanels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val panels = StudioCanvasState()
            val first = panels.view(StudioRoute.Thread(1)) { TextView(instrumentation.targetContext) }
            val second = panels.view(StudioRoute.Thread(2)) { TextView(instrumentation.targetContext) }
            val phone = panels.view(StudioRoute.Tab(StudioTab.PHONE)) { TextView(instrumentation.targetContext) }
            panels.invalidateThreads()
            assertNotSame(first, panels.view(StudioRoute.Thread(1)) { TextView(instrumentation.targetContext) })
            assertNotSame(second, panels.view(StudioRoute.Thread(2)) { TextView(instrumentation.targetContext) })
            assertSame(phone, panels.view(StudioRoute.Tab(StudioTab.PHONE)) { error("phone retained") })
        }
    }

    @Test fun mmsImageBubbleOpensShellAttachmentRoute() {
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val image = Attachment(9, 7, "content://mms/part/9", "image/png", 1, 1, "reference.png")
                val row = message(7, true).copy(attachment = MessageAttachment(7, "caption", arrayListOf(image)))
                var opened: Attachment? = null
                val adapter = StudioThreadAdapter(activity, viewAttachment = { opened = it }).apply { append(listOf(row)) }
                val parent = android.widget.FrameLayout(activity)
                val holder = adapter.onCreateViewHolder(parent, 0)
                adapter.onBindViewHolder(holder, 0)
                assertTrue(children(holder.itemView).any { it.contentDescription == "MMS image" })
                assertTrue(children(holder.itemView).filterIsInstance<TextView>().any { it.text == "reference.png" })
                children(holder.itemView).filterIsInstance<TextView>().single { it.text == "reference.png" }.performClick()
                assertSame(image, opened)
                val route = StudioRoute.Attachment(image.getUri(), image.mimetype, image.filename)
                assertEquals(route, Bundle().apply { putAll(route.encode()) }.decodeRoute())
                assertNotEquals(MessageKey(MessageOrigin.SMS, 7), row.let { MessageKey(MessageOrigin.MMS, it.id) })
            }
        }
    }

    @Test fun selectedHeldDraftLeadsReviewWhenNewerDraftSharesThread() {
        val older = ReviewDraft("older", "event-a", 42, "contact", "+15551234567",
            "Fixture", "First question", "First reply", 1, -1, 1, true)
        val newer = older.copy(draftId = "newer", eventId = "event-b", body = "Second reply", createdAt = 2)
        assertEquals(listOf(newer, older), prioritizeThreadDrafts(listOf(newer, older), null))
        assertEquals(listOf(older, newer), prioritizeThreadDrafts(listOf(newer, older), older.draftId))
    }

    @Test fun compactFocusedReviewShowsReplyAndKeepsSimActionReachable() {
        ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val draft = ReviewDraft("fixture-draft", "fixture-event", 42, "fixture-contact",
                    "+15551234567", "Fixture", "Can I book a consultation?",
                    "Sure! We can set up a 30-minute consultation. What date and time work best for you?",
                    1, -1, 0, true)
                val work = StudioWork(activity, Executor { it.run() })
                val source = object : StudioConversations {
                    override fun pageThreads(after: ThreadCursor?, limit: Int) =
                        Page<Conversation, ThreadCursor>(emptyList(), null)
                    override fun pageMessages(threadId: Long, after: MessageCursor?, limit: Int) =
                        Page<Message, MessageCursor>(emptyList(), null)
                    override fun markRead(threadId: Long) = Unit
                }
                val footer: (Long) -> View = {
                    StudioUi.scroll(activity, StudioUi.column(activity, 0).apply {
                        addView(InlineDraftReview(activity, work, {}).view(draft))
                    }).apply { layoutParams = android.widget.LinearLayout.LayoutParams(-1, StudioUi.dp(this, 280)) }
                }
                val thread = MessagePanel(activity, work, {}, {}, threadFooter = footer, gateway = source)
                    .thread(42, focusedReview = true)
                val canvas = activity.findViewById<android.widget.FrameLayout>(org.fossify.messages.R.id.inkpilot_canvas)
                canvas.removeAllViews()
                canvas.addView(thread, android.widget.FrameLayout.LayoutParams(
                    StudioUi.dp(canvas, 320), StudioUi.dp(canvas, 400)))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val canvas = activity.findViewById<View>(org.fossify.messages.R.id.inkpilot_canvas)
                val editor = children(canvas).filterIsInstance<android.widget.EditText>().single()
                val editorRect = android.graphics.Rect()
                assertTrue("held reply editor begins inside the focused thread viewport",
                    editor.getGlobalVisibleRect(editorRect) && editorRect.height() > editor.height / 2)
                val sim = children(canvas).filterIsInstance<TextView>()
                    .single { it.text.toString() == "Choose business SIM" }
                val scroll = children(canvas).filterIsInstance<android.widget.ScrollView>().single()
                scroll.requestChildRectangleOnScreen(sim,
                    android.graphics.Rect(0, 0, sim.width, sim.height), true)
                val simRect = android.graphics.Rect()
                assertTrue("staff can expose the SIM action before approval", sim.getGlobalVisibleRect(simRect))
                assertTrue("conversation retains a viewport",
                    children(canvas).filterIsInstance<RecyclerView>().single().height > 0)
            }
        }
    }

    private fun message(id: Long, mms: Boolean = false) = Message(id, "body $id", 1, -1, arrayListOf(), 100,
        false, 42, mms, null, "+15551234567", "Fixture", "", -1)
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 420, 600)
    }
}
