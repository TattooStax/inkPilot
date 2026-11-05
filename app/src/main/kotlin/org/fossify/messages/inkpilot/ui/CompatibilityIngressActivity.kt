package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import org.fossify.messages.messaging.captureSharedComposerImages
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.discardSharedComposerImages
import org.fossify.messages.messaging.OutgoingMessage

/** Retains historical component names without ever inflating a legacy messenger screen. */
open class CompatibilityIngressActivity : Activity() {
    protected open fun destination() = ExternalRouteParser.forward(this, intent)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val target = destination()
        val route = target.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute() as? StudioRoute.Compose
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) ||
            route?.mediaUris.isNullOrEmpty()) {
            startActivity(target)
            finish()
            return
        }
        val share = requireNotNull(route)
        // The sender's grant belongs to this task. Finish the bounded copy before opening a
        // composer that may survive the task or process, without blocking the UI thread.
        Thread {
            val local = captureSharedComposerImages(share.mediaUris)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    if (local != null) discardSharedComposerImages(local)
                    return@runOnUiThread
                }
                val saved = local != null && runCatching { ComposerStore(this).save(OutgoingMessage(share.composerId,
                    share.recipients.joinToString("|"), share.body, local, -1)) }.getOrDefault(false)
                if (saved && local != null) {
                    target.putExtra(ExternalRouteParser.ROUTE, share.copy(mediaUris = local).encode())
                    target.clipData = ClipData.newRawUri("Shared images", local.first()).apply {
                        local.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                } else {
                    if (local != null) discardSharedComposerImages(local)
                    target.putExtra(ExternalRouteParser.ROUTE, share.copy(shareCopyFailed = true).encode())
                }
                startActivity(target)
                finish()
            }
        }.start()
    }
}
