package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.messaging.*
import org.fossify.messages.models.Attachment

/** All changes are committed before navigation or carrier handoff. Never retries a claim. */
internal class ThreadComposer(private val activity: AppCompatActivity, private val work: StudioWork,
    private val pick: (Long) -> Unit, private val refresh: () -> Unit, private val open: (StudioRoute) -> Unit) {
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
        if (threadId > 0 && saved.recipient.isBlank()) work.load({ activity.getThreadParticipants(threadId, null).getAddresses().filter { it.isNotBlank() }.joinToString("|") }) {
            if (recipient.text.isBlank()) { recipient.setText(it); persist() }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { persist() }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        recipient.addTextChangedListener(watcher); text.addTextChangedListener(watcher)
        uris.forEach { uri -> root.addView(StudioUi.button(activity, "Remove attachment: ${uri.lastPathSegment}") { uris = uris - uri; persist(); refresh() }) }
        root.addView(StudioUi.button(activity, "Attach image, video or file") { persist(); pick(threadId) })
        val selector = StudioUi.button(activity, if (sim < 0) "Choose SIM" else "SIM subscription $sim") {}
        selector.setOnClickListener { chooseComposerSim(activity, work) { id, label -> sim = id; selector.text = label; persist() } }
        root.addView(selector)
        val status = StudioUi.text(activity, if (store.isUnknown(threadId)) "Send outcome unknown. Check the conversation; this draft cannot be sent again." else "")
        root.addView(status)
        val send = StudioUi.button(activity, "Send message") {}
        send.isEnabled = !store.isUnknown(threadId)
        send.setOnClickListener {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                status.text = "Choose a SIM after allowing SIM access"; return@setOnClickListener
            }
            val value = request()
            if (value.body.isBlank() && value.attachmentUris.isEmpty()) { status.text = "Write a message or attach a file"; return@setOnClickListener }
            send.isEnabled = false
            work.load({ prepareComposerSend(store, value) {
                val active = activity.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                val dispatcher = MessageDispatcher.local(activity) { outgoing ->
                    if (active.none { it.subscriptionId == outgoing.subscriptionId }) DispatchOutcome.Rejected("Choose an active SIM")
                    else {
                        activity.sendMessageCompat(outgoing.body, outgoing.recipient.split("|").filter { it.isNotBlank() }, outgoing.subscriptionId,
                        outgoing.attachmentUris.map { Attachment(null, 0, it.toString(), activity.contentResolver.getType(it) ?: "application/octet-stream", 0, 0, it.lastPathSegment.orEmpty()) })
                    }
                }
                dispatcher
            } }) { result ->
                val outcome = result.getOrElse {
                    status.text = "Unable to prepare message. Draft retained."
                    send.isEnabled = composerCanRetry(store, value)
                    return@load
                }
                when (outcome) {
                    is DispatchOutcome.Accepted -> {
                        refresh()
                        if (threadId <= 0L) work.load({ activity.getThreadId(value.recipient.split("|").filter { it.isNotBlank() }.toSet()) }) { open(StudioRoute.Thread(it)) }
                    }
                    is DispatchOutcome.Rejected -> { status.text = outcome.reason; send.isEnabled = composerCanRetry(store, value) }
                    is DispatchOutcome.Unknown -> status.text = "Send outcome unknown. Draft retained. Check the conversation before composing another message."
                }
            }
        }
        root.addView(send)
        if (threadId > 0) {
            // Opening a conversation must show its history. Staff can tap the
            // composer to bring up the keyboard when they are ready to reply.
            root.isFocusableInTouchMode = true
            root.requestFocus()
        }
        return root
    }
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
