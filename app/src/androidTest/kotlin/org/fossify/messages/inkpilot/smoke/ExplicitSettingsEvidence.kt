package org.fossify.messages.inkpilot.smoke

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.messages.inkpilot.ui.ExternalRouteParser
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.decodeRoute
import org.json.JSONObject
import java.io.File

/** Bounded route metadata only; never write recipient, message, URI or private view text. */
internal fun recordExplicitSettingsStage(context: Context, first: StudioHomeActivity,
                                         settings: StudioHomeActivity, title: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var safe = JSONObject()
    instrumentation.runOnMainSync {
        val route = settings.intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()
        safe = JSONObject().apply {
            put("firstInstance", System.identityHashCode(first))
            put("settingsInstance", System.identityHashCode(settings))
            put("firstTask", first.taskId)
            put("settingsTask", settings.taskId)
            put("routeKind", when (route) {
                is StudioRoute.Settings -> "settings"
                is StudioRoute.Tab -> "tab"
                is StudioRoute.Thread -> "thread"
                null -> "none"
                else -> "other"
            })
            put("locked", settings.appLockManager.isLocked())
            put("lifecycle", settings.lifecycle.currentState.name)
            put("finishing", settings.isFinishing)
            put("title", title.takeIf { it in setOf("Settings", "SMS", "Conversation", "Messages") } ?: "other")
        }
    }
    File(context.filesDir, "device-test-evidence/explicit-settings-stage.json").apply {
        parentFile!!.mkdirs(); writeText(safe.toString(2))
    }
}
