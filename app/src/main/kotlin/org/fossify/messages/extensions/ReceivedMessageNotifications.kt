package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

fun Context.showReceivedMessageNotification(
    messageId: Long,
    isMms: Boolean,
    address: String,
    senderName: String,
    body: String,
    threadId: Long,
    bitmap: Bitmap?,
    subscriptionId: Int? = null,
) {
    Handler(Looper.getMainLooper()).post {
        notificationHelper.showMessageNotification(
            messageId = messageId,
            isMms = isMms,
            address = address,
            body = body,
            threadId = threadId,
            bitmap = bitmap,
            sender = senderName,
            subscriptionId = subscriptionId,
        )
    }
}
