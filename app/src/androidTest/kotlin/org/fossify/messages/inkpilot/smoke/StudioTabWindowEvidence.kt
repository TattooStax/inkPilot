package org.fossify.messages.inkpilot.smoke

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Window and tab metadata only; never record visible text or customer content. */
internal fun recordStudioTabWindow(context: Context, requested: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun safePackage(node: AccessibilityNodeInfo?): String = when (node?.packageName?.toString()) {
        context.packageName -> "inkpilot"
        "android" -> "android"
        "com.android.permissioncontroller", "com.google.android.permissioncontroller" -> "permission-controller"
        null -> "none"
        else -> "other"
    }
    fun safeClass(node: AccessibilityNodeInfo?): String = when {
        node?.className == null -> "none"
        node.className.toString().startsWith("android.widget.") -> node.className.toString().substringAfterLast('.')
        node.className.toString().startsWith("android.view.") -> node.className.toString().substringAfterLast('.')
        node.className.toString().startsWith("org.fossify.messages.inkpilot.") -> "inkpilot-view"
        else -> "other"
    }
    fun tab(root: AccessibilityNodeInfo?, id: String): JSONObject {
        val item = root?.findAccessibilityNodeInfosByViewId("${context.packageName}:id/$id")?.firstOrNull()
        return JSONObject().apply {
            put("present", item != null)
            put("visible", item?.isVisibleToUser == true)
            put("clickable", item?.isClickable == true)
            put("selected", item?.isSelected == true)
        }
    }
    val active = automation.rootInActiveWindow
    val windows = runCatching { automation.windows }.getOrDefault(emptyList()).take(8)
    val requestedId = when (requested) {
        "SMS" -> "inkpilot_tab_sms"
        "Phone" -> "inkpilot_tab_phone"
        "Contacts" -> "inkpilot_tab_contacts"
        "Studio" -> "inkpilot_tab_studio"
        "Calendar" -> "inkpilot_tab_calendar"
        else -> "unknown"
    }
    val evidence = JSONObject().apply {
        put("requested", requested.takeIf { requestedId != "unknown" } ?: "unknown")
        put("activePackage", safePackage(active))
        put("activeClass", safeClass(active))
        put("activeRequestedTab", tab(active, requestedId))
        put("activeStudioTab", tab(active, "inkpilot_tab_studio"))
        put("activeContactsTab", tab(active, "inkpilot_tab_contacts"))
        put("activeCalendarTab", tab(active, "inkpilot_tab_calendar"))
        put("windowCount", windows.size)
        put("windows", JSONArray().apply {
            windows.forEach { window -> put(JSONObject().apply {
                put("type", window.type)
                put("layer", window.layer)
                put("active", window.isActive)
                put("focused", window.isFocused)
                put("package", safePackage(window.root))
                put("class", safeClass(window.root))
                put("requestedTab", tab(window.root, requestedId))
                put("studioTab", tab(window.root, "inkpilot_tab_studio"))
            }) }
        })
    }
    File(context.filesDir, "device-test-evidence/studio-tab-window.json").apply {
        parentFile!!.mkdirs(); writeText(evidence.toString(2))
    }
}
