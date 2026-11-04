package org.fossify.messages.inkpilot.ui

import android.content.Intent
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Message

/** SMS delivery-report status and MMS message-box values are separate provider namespaces. */
internal fun Message.deliveryLabel(): String = if (isMMS) when (type) {
    1 -> "Received"
    2 -> "Sent"
    3 -> "Draft"
    4 -> "Sending"
    5 -> "Failed"
    else -> "Unknown state"
} else when (type) {
    1 -> "Received"
    2 -> when { status == 0 -> "Delivered"; status >= 64 -> "Delivery failed"; status >= 32 -> "Delivery pending"; else -> "Sent" }
    3 -> "Draft"
    4 -> "Sending"
    5 -> "Failed"
    6 -> "Queued"
    else -> "Unknown state"
}

internal fun Attachment.viewIntent() = Intent(Intent.ACTION_VIEW).setDataAndType(getUri(), mimetype)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
