package org.fossify.messages.inkpilot.ui

import android.net.Uri
import android.os.Bundle

/** Host destinations; carrier and compatibility screens migrate to these incrementally. */
internal sealed interface StudioRoute {
    data class Tab(val tab: StudioTab) : StudioRoute
    data class ImageLinks(val contactId: String? = null, val threadId: Long? = null) : StudioRoute
    data class Thread(val threadId: Long) : StudioRoute
    data class Compose(val recipients: List<String>, val body: String, val mediaUris: List<Uri>) : StudioRoute
    data class Draft(val draftId: String) : StudioRoute
    data class Settings(val section: String) : StudioRoute
    data object Soul : StudioRoute
    data object Model : StudioRoute
    data class Auxiliary(val key: String) : StudioRoute
}

internal fun StudioRoute.encode() = Bundle().apply {
    putString("kind", when (this@encode) {
        is StudioRoute.ImageLinks -> "image-links"; is StudioRoute.Tab -> "tab"; is StudioRoute.Thread -> "thread"; is StudioRoute.Compose -> "compose"
        is StudioRoute.Draft -> "draft"; is StudioRoute.Settings -> "settings"
        StudioRoute.Soul -> "soul"; StudioRoute.Model -> "model"; is StudioRoute.Auxiliary -> "auxiliary"
    })
    when (val route = this@encode) {
        is StudioRoute.ImageLinks -> {
            putString("contact", route.contactId); route.threadId?.let { putLong("thread", it) }
        }
        is StudioRoute.Tab -> putString("value", route.tab.name)
        is StudioRoute.Thread -> putLong("thread", route.threadId)
        is StudioRoute.Compose -> {
            putStringArrayList("recipients", ArrayList(route.recipients)); putString("body", route.body)
            putStringArrayList("media", ArrayList(route.mediaUris.map(Uri::toString)))
        }
        is StudioRoute.Draft -> putString("value", route.draftId)
        is StudioRoute.Settings -> putString("value", route.section)
        is StudioRoute.Auxiliary -> putString("value", route.key)
        else -> Unit
    }
}

internal fun Bundle.decodeRoute(): StudioRoute? = when (getString("kind")) {
    "image-links" -> if (getString("contact") != null || containsKey("thread"))
        StudioRoute.ImageLinks(getString("contact"), if (containsKey("thread")) getLong("thread") else null) else null
    "tab" -> StudioTab.entries.find { it.name == getString("value") }?.let(StudioRoute::Tab)
    "thread" -> if (containsKey("thread")) StudioRoute.Thread(getLong("thread")) else null
    "compose" -> StudioRoute.Compose(getStringArrayList("recipients") ?: emptyList(), getString("body").orEmpty(),
        getStringArrayList("media").orEmpty().map(Uri::parse))
    "draft" -> getString("value")?.let(StudioRoute::Draft)
    "settings" -> getString("value")?.let(StudioRoute::Settings)
    "soul" -> StudioRoute.Soul
    "model" -> StudioRoute.Model
    "auxiliary" -> getString("value")?.let(StudioRoute::Auxiliary)
    else -> null
}
