package org.fossify.messages.inkpilot.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.fossify.messages.helpers.IS_RECYCLE_BIN
import org.fossify.messages.helpers.THREAD_ATTACHMENT_URI
import org.fossify.messages.helpers.THREAD_ATTACHMENT_URIS
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.THREAD_TEXT

/** Converts only known Android and historical messenger entry points to shell destinations. */
internal object ExternalRouteParser {
    const val ROUTE = "inkpilot.route"
    private val schemes = setOf("sms", "smsto", "mms", "mmsto")

    fun parse(intent: Intent): StudioRoute? {
        val oldComponent = intent.component?.className.orEmpty().substringAfterLast('.')
        if (intent.getBooleanExtra(IS_RECYCLE_BIN, false)) return StudioRoute.Auxiliary("recycle")
        when (oldComponent) {
            "ArchivedConversationsActivity" -> return StudioRoute.Auxiliary("archive")
            "RecycleBinConversationsActivity" -> return StudioRoute.Auxiliary("recycle")
            "SettingsActivity" -> return StudioRoute.Settings("messaging")
            "ConversationDetailsActivity" -> return StudioRoute.Auxiliary("details:${intent.getLongExtra(THREAD_ID, 0)}")
            "VCardViewerActivity" -> return StudioRoute.Tab(StudioTab.CONTACTS)
        }
        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) return StudioRoute.Settings("messaging")
        val thread = intent.getLongExtra(THREAD_ID, 0L)
        val body = listOfNotNull(intent.getCharSequenceExtra("sms_body")?.toString(),
            intent.getCharSequenceExtra(THREAD_TEXT)?.toString(),
            smsUriBody(intent.data),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()).firstOrNull().orEmpty()
        val media = media(intent)
        val recipients = recipients(intent)
        if (thread > 0 && body.isBlank() && media.isEmpty()) return StudioRoute.Thread(thread)
        if (intent.action in setOf(Intent.ACTION_SENDTO, Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) ||
            oldComponent == "NewConversationActivity" || body.isNotBlank() || media.isNotEmpty()) {
            if (intent.action in setOf(Intent.ACTION_SENDTO, Intent.ACTION_VIEW) && intent.data?.scheme !in schemes && thread <= 0) return null
            return StudioRoute.Compose(recipients, body, media)
        }
        return when (oldComponent) {
            "ThreadActivity" -> if (thread > 0) StudioRoute.Thread(thread) else StudioRoute.Tab(StudioTab.SMS)
            "MainActivity", "SplashActivity" -> StudioRoute.Tab(StudioTab.SMS)
            else -> null
        }
    }

    private fun smsUriBody(uri: Uri?): String? {
        if (uri?.scheme !in schemes) return null
        val query = uri?.encodedSchemeSpecificPart?.substringAfter('?', "").orEmpty()
        return query.split('&').firstNotNullOfOrNull { entry ->
            val key = entry.substringBefore('=', "")
            if (Uri.decode(key) == "body" && '=' in entry) {
                Uri.decode(entry.substringAfter('=')).takeIf(String::isNotEmpty)
            } else null
        }
    }

    fun media(intent: Intent): List<Uri> {
        val candidates = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        fun addExtra(key: String) {
            when (val value = intent.extras?.get(key)) {
                is Uri -> candidates.add(value)
                is String -> candidates.add(Uri.parse(value))
                is List<*> -> candidates.addAll(value.mapNotNull { when (it) {
                    is Uri -> it
                    is String -> Uri.parse(it)
                    else -> null
                } })
            }
        }
        addExtra(Intent.EXTRA_STREAM)
        addExtra(THREAD_ATTACHMENT_URI)
        addExtra(THREAD_ATTACHMENT_URIS)
        intent.clipData?.let { clip -> (0 until clip.itemCount).forEach { clip.getItemAt(it).uri?.let(candidates::add) } }
        return candidates.filter { it.scheme == "content" }.distinct()
    }

    private fun recipients(intent: Intent): List<String> {
        val uri = intent.data?.takeIf { it.scheme in schemes }?.schemeSpecificPart?.substringBefore('?')
        val candidate = intent.getStringExtra("address") ?: intent.getStringExtra(THREAD_NUMBER) ?: uri.orEmpty()
        val numbers = if (candidate.startsWith('[') && candidate.endsWith(']')) runCatching {
            JSONArray(candidate).let { array -> (0 until array.length()).map { array.getString(it) } }
        }.getOrElse { emptyList() } else candidate.split(',', ';')
        return numbers.map(Uri::decode).map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 100 }.distinct()
    }

    /** Grants are carried by ClipData, not only parcelable extras, across the forwarding Activity. */
    fun forward(context: Context, source: Intent): Intent {
        val destination = Intent(context, StudioHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        parse(source)?.let { destination.putExtra(ROUTE, it.encode()) }
        val media = media(source)
        if (media.isNotEmpty()) {
            val clip = ClipData.newRawUri("Shared images", media.first())
            media.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            destination.clipData = clip
            destination.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (source.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        }
        return destination
    }
}
