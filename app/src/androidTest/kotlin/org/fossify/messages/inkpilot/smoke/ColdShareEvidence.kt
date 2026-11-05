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

/** Bounded lifecycle metadata; never persist a URI, message, contact, or view text. */
internal fun recordColdShareFailure(target: Context, sharedUri: Uri) {
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
    val evidence = JSONObject().apply {
        put("shareAliasResolved", runCatching { target.packageManager.getActivityInfo(alias, 0).exported }.getOrDefault(false))
        put("unlockResumed", unlockResumed)
        put("homes", stages)
    }
    File(target.filesDir, "device-test-evidence/cold-share-stage.json").apply {
        parentFile!!.mkdirs(); writeText(evidence.toString(2))
    }
}
