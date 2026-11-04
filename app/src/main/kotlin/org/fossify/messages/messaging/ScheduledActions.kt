package org.fossify.messages.messaging

import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.models.RadioItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message

internal fun SimpleActivity.showScheduledActions(message: Message, onEdit: () -> Unit, onDelete: () -> Unit) {
    val queued = ScheduledVerdicts(this).use { it.state(message.key()) == "QUEUED" }
    toast(scheduledStatus(message.key()))
    val items = arrayListOf(RadioItem(0, getString(org.fossify.commons.R.string.delete)))
    if (queued) {
        items.add(0, RadioItem(1, getString(R.string.send_now)))
        items.add(0, RadioItem(2, getString(R.string.update_message)))
    }
    RadioGroupDialog(this, items, titleId = R.string.scheduled_message) { selected ->
        when (selected as Int) {
            0 -> onDelete()
            2 -> onEdit()
            1 -> ensureBackgroundThread {
                val outcome = dispatchScheduled(message)
                if (outcome !is DispatchOutcome.Rejected) cancelScheduleSendPendingIntent(message.key())
                runOnUiThread { toast(if (outcome is DispatchOutcome.Rejected) outcome.reason else scheduledStatus(message.key())) }
                refreshMessages()
            }
        }
    }
}
