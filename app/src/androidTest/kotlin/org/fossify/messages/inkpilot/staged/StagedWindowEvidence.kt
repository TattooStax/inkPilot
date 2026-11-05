package org.fossify.messages.inkpilot.staged

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.json.JSONArray
import org.json.JSONObject

/** Only allowlisted window classes and booleans; no dialog text or client data. */
internal fun stagedWindowEvidence(context: Context): JSONObject {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun packageKind(node: AccessibilityNodeInfo?): String = when (node?.packageName?.toString()) {
        context.packageName -> "inkpilot"
        "android" -> "android"
        "com.android.permissioncontroller", "com.google.android.permissioncontroller" -> "permission"
        null -> "none"
        else -> "other"
    }
    fun classKind(node: AccessibilityNodeInfo?): String = when (node?.className?.toString()) {
        "android.widget.FrameLayout" -> "frame"
        "android.widget.LinearLayout" -> "linear"
        "android.widget.ListView" -> "list"
        "android.widget.ScrollView" -> "scroll"
        "android.widget.TextView" -> "text"
        null -> "none"
        else -> "other"
    }
    fun hasNext(root: AccessibilityNodeInfo?) = root?.findAccessibilityNodeInfosByText("Next")
        ?.any { it.text?.toString() == "Next" && it.isVisibleToUser && it.isClickable } == true
    fun windowKind(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility"
        else -> "other"
    }
    val active = automation.rootInActiveWindow
    val windows = runCatching { automation.windows }.getOrDefault(emptyList()).take(8)
    return JSONObject().apply {
        put("activePackage", packageKind(active))
        put("activeClass", classKind(active))
        put("activeHasNext", hasNext(active))
        put("locked", context.appLockManager.isLocked())
        put("windowCount", windows.size)
        put("windows", JSONArray().apply {
            windows.forEach { window -> put(JSONObject().apply {
                put("kind", windowKind(window.type))
                put("layer", window.layer)
                put("active", window.isActive)
                put("focused", window.isFocused)
                put("package", packageKind(window.root))
                put("class", classKind(window.root))
                put("hasNext", hasNext(window.root))
            }) }
        })
    }
}
