package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat

internal class SmsRefreshObserver(
    private val permitted: () -> Boolean,
    private val register: (Uri, ContentObserver) -> Unit,
    private val unregister: (ContentObserver) -> Unit,
    private val refresh: () -> Unit,
) {
    constructor(context: Context, refresh: () -> Unit) : this(
        { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED },
        { uri, observer -> context.contentResolver.registerContentObserver(uri, true, observer) },
        { observer -> context.contentResolver.unregisterContentObserver(observer) }, refresh,
    )
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private val reload = Runnable { if (registered && permitted()) refresh() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!registered) return
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 300)
        }
    }
    fun start() {
        if (!permitted()) { stop(); return }
        if (registered) return
        try {
            register(Telephony.MmsSms.CONTENT_URI, observer)
            register(Telephony.Sms.CONTENT_URI, observer)
            registered = true
        } catch (_: RuntimeException) {
            // The first registration can succeed before the second fails (including revocation).
            runCatching { unregister(observer) }
            handler.removeCallbacks(reload)
        }
    }
    fun stop() {
        val wasRegistered = registered
        registered = false
        handler.removeCallbacks(reload)
        if (wasRegistered) runCatching { unregister(observer) }
    }
}
