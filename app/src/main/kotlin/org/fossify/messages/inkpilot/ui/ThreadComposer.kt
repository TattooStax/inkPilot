package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.messaging.*

/** All changes are committed before navigation or carrier handoff. Never retries a claim. */
internal class ThreadComposer(private val activity: AppCompatActivity, private val work: StudioWork,
    private val pick: (Long) -> Unit, private val refresh: () -> Unit, private val open: (StudioRoute) -> Unit,
    private val accepted: (Long) -> Unit = { refresh() }) {
    fun view(threadId: Long, initial: StudioRoute.Compose? = null): android.widget.LinearLayout {
        val store = ComposerStore(activity)
        val saved = store.getOrCreate(OutgoingMessage(threadId, initial?.recipients?.joinToString("|").orEmpty(),
            initial?.body.orEmpty(), initial?.mediaUris.orEmpty(), -1))
        val root = StudioUi.column(activity, 8)
        val recipient = EditText(activity).apply {
            id = android.view.View.generateViewId()
            hint = "Recipients separated by |"
            setText(saved.recipient)
        }
        val text = EditText(activity).apply {
            id = android.view.View.generateViewId()
            hint = "Message"
            minLines = 2
            maxLines = 5
            setText(saved.body)
        }
        var uris = saved.attachmentUris
        var sim = saved.subscriptionId
        fun request() = OutgoingMessage(threadId, recipient.text.toString(), text.text.toString(), uris, sim, saved.attemptId)
        fun persist() { if (!store.save(request())) StudioUi.error(activity, "Unable to save composer") }
        root.addView(StudioUi.text(activity, "Recipients", 14f).apply { labelFor = recipient.id })
        root.addView(recipient)
        root.addView(StudioUi.text(activity, "Message", 14f).apply { labelFor = text.id })
        root.addView(text)
        loadRecipientForThread(activity, work, store, threadId, saved.recipient, recipient, ::persist)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { persist() }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        recipient.addTextChangedListener(watcher); text.addTextChangedListener(watcher)
        val editable = mutableListOf<View>(recipient, text)
        uris.forEach { uri ->
            val remove = StudioUi.button(activity, "Remove attachment: ${uri.lastPathSegment}") {
                uris = uris - uri; persist(); refresh()
            }
            root.addView(remove)
            editable.add(remove)
        }
        val attach = StudioUi.button(activity, "Attach image (up to 1 MiB)") { persist(); pick(threadId) }
        root.addView(attach)
        editable.add(attach)
        val selector = StudioUi.button(activity, if (sim < 0) "Choose SIM" else "SIM subscription $sim") {}
        selector.setOnClickListener { chooseComposerSim(activity, work) { id, label ->
            sim = id; selector.text = label; persist()
        } }
        root.addView(selector)
        editable.add(selector)
        fun enableEditor(enabled: Boolean) { editable.forEach { it.isEnabled = enabled } }
        enableEditor(!store.isUnknown(threadId))
        val status = StudioUi.text(activity, initialComposerStatus(store, threadId))
        root.addView(status)
        val send = StudioUi.button(activity, "Send message") {}
        send.isEnabled = !store.isUnknown(threadId)
        val controls = ComposerSendControls(status, send, { request() },
            { enabled -> enableEditor(enabled) }, refresh, open, accepted)
        val controller = InShellSendController(activity, work, store, threadId, controls)
        send.setOnClickListener { controller.onSend() }
        root.addView(send)
        root.focusHistoryOnOpen(threadId)
        return root
    }
}

private fun android.widget.LinearLayout.focusHistoryOnOpen(threadId: Long) {
    if (threadId <= 0L) return
    // Opening a conversation must show its history. Staff can tap the composer to reply.
    isFocusableInTouchMode = true
    requestFocus()
}

internal fun chooseComposerSim(activity: AppCompatActivity, work: StudioWork, chosen: (Int, String) -> Unit) {
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 7303)
        StudioUi.error(activity, "Allow SIM access, then choose a SIM again."); return
    }
    work.load({ activity.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() }) { sims ->
        if (sims.isEmpty()) { StudioUi.error(activity, "No active SIM. Draft retained."); return@load }
        val labels = sims.map { "${it.displayName} · SIM ${it.simSlotIndex + 1}" }
        androidx.appcompat.app.AlertDialog.Builder(activity).setTitle("Choose SIM").setItems(labels.toTypedArray()) { _, i -> chosen(sims[i].subscriptionId, labels[i]) }.setNegativeButton("Cancel", null).show()
    }
}

/** Retry is permitted only before durable handoff intent exists. */
internal fun composerCanRetry(store: ComposerStore, request: OutgoingMessage): Boolean =
    !store.isUnknown(request.threadId) && !store.wasSubmitted(request)

internal fun prepareComposerSend(store: ComposerStore, request: OutgoingMessage,
    prepare: () -> MessageDispatcher): Result<DispatchOutcome> = runCatching { store.submit(request, prepare()) }
