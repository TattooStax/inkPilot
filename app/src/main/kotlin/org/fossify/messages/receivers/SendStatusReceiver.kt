package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread

abstract class SendStatusReceiver : BroadcastReceiver() {
    // Updates the status of the message in the internal database
    abstract fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int): Boolean

    // allows the implementer to update the status of the message in their database
    abstract fun updateAppDatabase(
        context: Context, intent: Intent, receiverResultCode: Int, nativeRowVerified: Boolean,
    )

    override fun onReceive(context: Context, intent: Intent) {
        val resultCode = resultCode
        val pending = goAsync()
        ensureBackgroundThread {
            try {
                finishSendStatusCallback(
                    updateNative = { updateAndroidDatabase(context, intent, resultCode) },
                    updateApp = { verified -> updateAppDatabase(context, intent, resultCode, verified) },
                )
            } finally { pending.finish() }
        }
    }

    companion object {
        const val SMS_SENT_ACTION = "org.fossify.org.fossify.messages.receiver.SMS_SENT"
        const val SMS_DELIVERED_ACTION = "org.fossify.org.fossify.messages.receiver.SMS_DELIVERED"

        // Platform callback extras documented by SmsManager.sendTextMessage.
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_NO_DEFAULT = "noDefault"
        const val EXTRA_SUB_ID = "subId"

        const val NO_ERROR_CODE = -1
    }
}

/** The app cache and scheduled journal must observe the native callback result. */
internal fun finishSendStatusCallback(updateNative: () -> Boolean, updateApp: (Boolean) -> Unit) {
    val nativeRowVerified = updateNative()
    updateApp(nativeRowVerified)
}

/** An update count alone cannot confirm a send if the provider readback disagrees. */
internal fun nativeStatusUpdateVerified(expected: Int, update: () -> Int, read: () -> Int?): Boolean =
    update() > 0 && read() == expected
