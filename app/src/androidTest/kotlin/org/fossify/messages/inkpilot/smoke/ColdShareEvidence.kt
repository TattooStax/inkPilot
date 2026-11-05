package org.fossify.messages.inkpilot.smoke

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.fossify.commons.activities.AppLockActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.ui.ExternalRouteParser
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.decodeRoute
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

private fun readShell(command: String): String? = runCatching {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { pipe ->
        FileInputStream(pipe.fileDescriptor).bufferedReader().use { it.readText() }
    }
}.getOrNull()

/** Bounded lifecycle metadata; never persist a URI, message, contact, or view text. */
internal fun recordColdShareFailure(target: Context, sender: Context, sharedUri: Uri) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var stages = JSONArray()
    instrumentation.runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        stages = JSONArray().apply {
            Stage.values().forEach { stage ->
                monitor.getActivitiesInStage(stage).filterIsInstance<StudioHomeActivity>().take(4).forEach { home ->
                    val route = home.intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()
                    put(JSONObject().apply {
                        put("stage", stage.name)
                        put("taskId", home.taskId)
                        put("finishing", home.isFinishing)
                        put("locked", home.appLockManager.isLocked())
                        put("routeKind", when (route) {
                            is StudioRoute.Compose -> "compose"
                            is StudioRoute.Tab -> "tab"
                            null -> "none"
                            else -> "other"
                        })
                        put("hasSharedUri", (route as? StudioRoute.Compose)?.mediaUris?.contains(sharedUri) == true)
                        put("hasGrantFlag", home.intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    })
                }
            }
        }
    }
    val unlockResumed = runCatching {
        var result = false
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is AppLockActivity }
        }
        result
    }.getOrDefault(false)
    val alias = ComponentName(target.packageName, "org.fossify.messages.activities.NewConversationActivity")
    val senderActivity = ComponentName(sender.packageName, CrossPackageShareSender::class.java.name)
    val senderMarker = File(sender.cacheDir, "task11-share-stage")
    val senderRead = runCatching { senderMarker.readText() }.getOrNull()
    val activityTasks = readShell("dumpsys activity activities")
    val activityLog = readShell("logcat -d -t 2000 -v brief -s ActivityTaskManager:I ActivityManager:I AndroidRuntime:E")
    val senderCrash = activityLog?.substringAfterLast("FATAL EXCEPTION")
        ?.takeIf { it.contains("CrossPackageShareSender") }
    val cause = senderCrash?.let { crash ->
        Regex("Caused by: ([A-Za-z0-9_.]+(?:Exception|Error))").findAll(crash).lastOrNull()?.groupValues?.get(1)
    }?.substringAfterLast('.')
    val allowedCauses = setOf("ClassNotFoundException", "NoClassDefFoundError", "SecurityException",
        "IllegalArgumentException", "IllegalStateException", "FileNotFoundException", "IOException",
        "RuntimeException", "OutOfMemoryError", "UnsatisfiedLinkError")
    val firstFrame = senderCrash?.lineSequence()?.firstOrNull { it.contains("at ") }?.substringAfter("at ")
    val evidence = JSONObject().apply {
        put("shareAliasResolved", runCatching { target.packageManager.getActivityInfo(alias, 0).exported }.getOrDefault(false))
        put("senderActivityResolved", runCatching { sender.packageManager.getActivityInfo(senderActivity, 0).exported }.getOrDefault(false))
        put("senderCacheReadable", senderMarker.parentFile?.canRead() == true)
        put("senderMarkerReadable", senderRead != null)
        put("senderStage", senderRead?.takeIf { it in setOf("prepared", "forwarded") } ?: "missing")
        put("activityTasksReadable", activityTasks != null)
        put("senderInTask", activityTasks?.contains("CrossPackageShareSender") == true)
        put("shareAliasInTask", activityTasks?.contains("NewConversationActivity") == true)
        put("homeInTask", activityTasks?.contains("StudioHomeActivity") == true)
        put("activityLogReadable", activityLog != null)
        put("backgroundLaunchBlocked", activityLog?.contains("Background activity start denied", ignoreCase = true) == true ||
            activityLog?.contains("Background activity launch blocked", ignoreCase = true) == true)
        put("senderCrashLogged", activityLog?.contains("FATAL EXCEPTION") == true &&
            activityLog?.contains("CrossPackageShareSender") == true)
        put("senderCrashCause", cause?.takeIf { it in allowedCauses } ?: "other")
        put("senderFirstFrame", when {
            firstFrame?.contains("CrossPackageShareSender") == true -> "sender"
            firstFrame?.contains("ActivityThread") == true -> "activityThread"
            firstFrame?.contains("Instrumentation") == true -> "instrumentation"
            firstFrame == null -> "none"
            else -> "other"
        })
        put("senderOnCreateFrame", senderCrash?.contains("CrossPackageShareSender.onCreate") == true)
        put("senderBitmapFrame", senderCrash?.contains("android.graphics.Bitmap") == true)
        put("senderFileFrame", senderCrash?.contains("java.io.FileOutputStream") == true)
        put("unlockResumed", unlockResumed)
        put("homes", stages)
    }
    File(target.filesDir, "device-test-evidence/cold-share-stage.json").apply {
        parentFile!!.mkdirs(); writeText(evidence.toString(2))
    }
}
