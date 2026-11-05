package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.fossify.messages.R

private const val THREAD_VIEWPORT_SHARE = 2
private const val FOCUSED_REVIEW_PARTS = 3
private const val FOCUSED_REVIEW_TOTAL = 4
private const val READ_ONLY_MESSAGE_SIZE_SP = 14f

internal class MessagePanel(
    private val activity: AppCompatActivity,
    private val work: StudioWork,
    private val setup: () -> Unit,
    private val drafts: () -> Unit,
    private val open: (StudioRoute) -> Unit = {},
    private val threadFooter: ((Long) -> View)? = null,
    private val gateway: StudioConversations = ConversationGateway(activity.applicationContext),
    private val importImage: (org.fossify.messages.messaging.MessageKey, Long, org.fossify.messages.models.Attachment) -> Unit = { _, _, _ -> },
) {
    fun messages(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.button(activity, "Drafts to review", drafts))
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            body.addView(StudioUi.button(activity, "Set up SMS", setup).apply { id = R.id.inkpilot_sms_setup })
            return body
        }
        body.addView(StudioUi.button(activity, "New message") { open(StudioRoute.Compose(emptyList(), "", emptyList())) }
            .apply { id = R.id.inkpilot_new_message })
        val adapter = InboxAdapter(activity) { open(StudioRoute.Thread(it)) }
        val list = recycler().apply { id = R.id.inkpilot_message_list; this.adapter = adapter }
        body.addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        var cursor: ThreadCursor? = null
        var loading = false
        var complete = false
        fun load() {
            if (loading || complete) return
            loading = true
            work.load({ gateway.pageThreads(cursor, 40) }, { loading = false }) { page ->
                adapter.append(page.items); cursor = page.next; complete = page.next == null; loading = false
                list.contentReady(adapter.itemCount, !complete)
            }
        }
        list.loadMore = ::load
        list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(view: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!view.canScrollVertically(1)) load()
            }
        })
        body.addView(StudioUi.button(activity, "Load more conversations") { load() })
        load()
        return body
    }

    /** Task10 can supply a composer footer without replacing the shell or message list. */
    fun thread(threadId: Long, focusedReview: Boolean = false): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.button(activity, "Client reference images") { open(StudioRoute.ImageLinks(threadId = threadId)) })
        val adapter = StudioThreadAdapter(activity, forward = { message ->
            open(StudioRoute.Compose(emptyList(), message.body, message.attachment?.attachments?.map { it.getUri() }.orEmpty()))
        }, importImage = importImage, viewAttachment = { attachment ->
            open(StudioRoute.Attachment(attachment.getUri(), attachment.mimetype, attachment.filename))
        })
        val list = recycler().apply {
            id = R.id.inkpilot_thread_message_list
            this.adapter = adapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity).apply { reverseLayout = true }
        }
        body.addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        var cursor: MessageCursor? = null
        var loading = false
        var complete = false
        fun load() {
            if (loading || complete) return
            loading = true
            work.load({ gateway.pageMessages(threadId, cursor, 40) }, { loading = false }) { page ->
                adapter.append(page.items); cursor = page.next; complete = page.next == null; loading = false
                list.contentReady(adapter.itemCount, !complete)
            }
        }
        list.loadMore = ::load
        list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(view: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!view.canScrollVertically(-1)) load()
            }
        })
        body.addView(StudioUi.button(activity, "Load older messages") { load() }, 0)
        val footer = threadFooter?.invoke(threadId)
            ?: StudioUi.text(activity, "Read-only conversation", READ_ONLY_MESSAGE_SIZE_SP)
        body.addView(footer)
        if (threadFooter != null) reserveThreadViewport(body, list, footer, focusedReview)
        work.load({ gateway.markRead(threadId) }) { }
        load()
        return body
    }

    private fun reserveThreadViewport(
        body: android.widget.LinearLayout, list: View, footer: View, focusedReview: Boolean,
    ) {
        val preferredFooterHeight = footer.layoutParams.height
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val fixed = (0 until body.childCount).sumOf { index ->
                body.getChildAt(index).takeUnless { it === list || it === footer }?.height ?: 0
            }
            val room = (bottom - top - body.paddingTop - body.paddingBottom - fixed).coerceAtLeast(0)
            val share = if (focusedReview) room * FOCUSED_REVIEW_PARTS / FOCUSED_REVIEW_TOTAL
                else room / THREAD_VIEWPORT_SHARE
            val footerHeight = minOf(preferredFooterHeight, share)
            if (footer.layoutParams.height != footerHeight) {
                footer.layoutParams = (footer.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    height = footerHeight
                }
            }
        }
    }

    private fun recycler() = StudioPagedList(activity).apply {
        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
        // Hierarchy restoration waits until the asynchronous first page is present.
        setHasFixedSize(false)
    }

    fun phone(initial: String = ""): View = org.fossify.messages.inkpilot.phone.PhonePanel(activity,
        openHistory = { open(StudioRoute.PhoneHistory) },
        openContacts = { open(StudioRoute.Tab(StudioTab.CONTACTS)) }).view(initial)
}
