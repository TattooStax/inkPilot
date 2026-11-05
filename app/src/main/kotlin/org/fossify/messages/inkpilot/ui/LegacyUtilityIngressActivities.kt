package org.fossify.messages.inkpilot.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.fossify.messages.helpers.EXTRA_VCARD_URI
import org.fossify.messages.helpers.THREAD_ID

/** Android may replace an activity-alias component with its target before onCreate. */
class SettingsIngressActivity : CompatibilityIngressActivity() {
    override fun destination() = super.destination().putExtra(
        ExternalRouteParser.ROUTE, StudioRoute.Settings("messaging").encode())
}

class ArchiveIngressActivity : CompatibilityIngressActivity() {
    override fun destination() = super.destination().putExtra(
        ExternalRouteParser.ROUTE, StudioRoute.Auxiliary("archive").encode())
}

class RecycleIngressActivity : CompatibilityIngressActivity() {
    override fun destination() = super.destination().putExtra(
        ExternalRouteParser.ROUTE, StudioRoute.Auxiliary("recycle").encode())
}

class DetailsIngressActivity : CompatibilityIngressActivity() {
    override fun destination() = super.destination().putExtra(
        ExternalRouteParser.ROUTE, StudioRoute.Auxiliary("details:${intent.getLongExtra(THREAD_ID, 0L)}").encode())
}

class VCardIngressActivity : CompatibilityIngressActivity() {
    override fun destination(): Intent {
        @Suppress("DEPRECATION")
        val uri = ((intent.getParcelableExtra(EXTRA_VCARD_URI) as? Uri) ?: intent.data)
            ?.takeIf { it.scheme == "content" }
        val route = uri?.let { StudioRoute.Attachment(it, "text/vcard", "Contact card") }
            ?: StudioRoute.Tab(StudioTab.CONTACTS)
        return super.destination().putExtra(ExternalRouteParser.ROUTE, route.encode()).apply {
            if (uri != null) {
                clipData = ClipData.newRawUri("Contact card", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
