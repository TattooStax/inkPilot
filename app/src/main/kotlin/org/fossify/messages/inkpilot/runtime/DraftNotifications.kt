package org.fossify.messages.inkpilot.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.fossify.messages.R
import org.fossify.messages.inkpilot.ui.StudioHomeActivity

internal object DraftNotifications {
    fun show(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("inkpilot-drafts", "Drafts for review", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, StudioHomeActivity::class.java).putExtra("inkpilot_panel", "drafts")
        val pending = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "inkpilot-drafts")
            .setSmallIcon(R.drawable.inkpilot_nav_sms).setContentTitle("Drafts ready for review")
            .setContentText("$count local draft(s) are waiting for staff approval.")
            .setContentIntent(pending).setAutoCancel(true).build()
        runCatching { manager.notify(8401, notification) }
    }
}
