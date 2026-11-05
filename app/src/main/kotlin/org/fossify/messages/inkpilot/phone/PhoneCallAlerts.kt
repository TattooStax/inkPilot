package org.fossify.messages.inkpilot.phone

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.inkpilot.ui.StudioUi

/** The manual incoming-call UI needs visible notifications and full-screen access on locked devices. */
internal object PhoneCallAlerts {
    const val CHANNEL = "manual_calls"
    private const val POST_REQUEST = 722
    private const val PREFS = "phone_alerts"
    private const val POST_ATTEMPTED = "post_attempted"

    internal enum class PostRoute { REQUEST, SETTINGS }

    /** A second attempt should open Settings, including when Android no longer shows a permission dialog. */
    internal fun nextPostRoute(context: Context): PostRoute {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(POST_ATTEMPTED, false)) return PostRoute.SETTINGS
        prefs.edit().putBoolean(POST_ATTEMPTED, true).apply()
        return PostRoute.REQUEST
    }

    enum class Block(val guidance: String, val action: String) {
        POST("Allow notifications so inkPilot can show incoming calls.", "Allow phone notifications"),
        APP("Enable inkPilot notifications to see incoming calls.", "Open notification settings"),
        CHANNEL("Set the Phone calls notification channel to high importance.", "Open call alert settings"),
        FULL_SCREEN("Enable full-screen call alerts for incoming calls on the lock screen.",
            "Open full-screen alert settings"),
    }

    fun createChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone calls", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Kept pure so denied notification and full-screen states can be tested without changing roles. */
    fun evaluate(post: Boolean, app: Boolean, channelImportance: Int, fullScreen: Boolean): Block? = when {
        !post -> Block.POST
        !app -> Block.APP
        channelImportance < NotificationManager.IMPORTANCE_HIGH -> Block.CHANNEL
        !fullScreen -> Block.FULL_SCREEN
        else -> null
    }

    fun blocker(context: Context): Block? {
        val manager = context.getSystemService(NotificationManager::class.java)
        val post = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (post && manager.areNotificationsEnabled()) createChannel(manager)
        return evaluate(post, manager.areNotificationsEnabled(),
            manager.getNotificationChannel(CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent())
    }

    /** The notification has no lock-screen call actions, so do not take the role without full-screen access. */
    fun ensure(activity: Activity): Boolean {
        val block = blocker(activity) ?: return true
        address(activity, block)
        return false
    }

    fun address(activity: Activity) {
        val block = blocker(activity) ?: return
        address(activity, block)
    }

    private fun address(activity: Activity, block: Block) {
        when (block) {
            Block.POST -> when (nextPostRoute(activity)) {
                PostRoute.REQUEST -> ActivityCompat.requestPermissions(activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_REQUEST)
                PostRoute.SETTINGS -> openSettings(activity, block, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            Block.APP -> openSettings(activity, block, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            Block.CHANNEL -> openSettings(activity, block, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL))
            Block.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                openSettings(activity, block, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.fromParts("package", activity.packageName, null)))
            }
        }
    }

    private fun openSettings(activity: Activity, block: Block, intent: Intent) {
        runCatching { activity.startActivity(intent) }.onFailure {
            runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null))) }
                .onFailure { StudioUi.error(activity, block.guidance) }
        }
    }
}
