package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat

internal class SmsRefreshObserver(private val context: Context, private val refresh: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable(refresh)
    private var registered = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 300)
        }
    }
    fun start() {
        if (registered || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        registered = runCatching {
            context.contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            true
        }.getOrDefault(false)
    }
    fun stop() {
        handler.removeCallbacks(reload)
        if (registered) context.contentResolver.unregisterContentObserver(observer)
        registered = false
    }
}
