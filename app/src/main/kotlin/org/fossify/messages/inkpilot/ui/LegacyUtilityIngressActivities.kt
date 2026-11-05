package org.fossify.messages.inkpilot.ui

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
    override fun destination() = super.destination().putExtra(
        ExternalRouteParser.ROUTE, StudioRoute.Tab(StudioTab.CONTACTS).encode())
}
