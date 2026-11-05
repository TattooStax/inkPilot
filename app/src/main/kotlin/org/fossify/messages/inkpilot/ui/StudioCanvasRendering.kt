package org.fossify.messages.inkpilot.ui

import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.phone.PhoneHistoryPanel
import org.fossify.messages.inkpilot.studio.StudioPanels

internal fun supplementalCanvas(
    activity: StudioHomeActivity,
    route: StudioRoute,
    messages: MessagePanel,
    work: StudioWork,
    repository: StudioRepository,
    back: () -> Unit,
): android.view.View? = when (route) {
    is StudioRoute.PhoneDial -> messages.phone(route.number)
    StudioRoute.PhoneHistory -> PhoneHistoryPanel(activity, work,
        { activity.openRoute(StudioRoute.PhoneDial(it)) }, back).view()
    is StudioRoute.Attachment -> AttachmentPanel(activity, back, repository, work).view(route)
    else -> null
}

internal fun routeTitle(route: StudioRoute, tab: StudioTab): String = when (route) {
    is StudioRoute.Tab -> tab.label
    is StudioRoute.Thread -> "Conversation"
    is StudioRoute.Compose -> "New message"
    is StudioRoute.PhoneDial -> "Phone"
    StudioRoute.PhoneHistory -> "Call history"
    is StudioRoute.Attachment -> "Attachment"
    is StudioRoute.ImageLinks -> "Client References"
    is StudioRoute.Settings -> "Settings"
    is StudioRoute.Auxiliary -> route.key.substringBefore(':').replaceFirstChar { it.uppercase() }
    else -> "Assistant"
}

internal fun auxiliaryCanvas(
    activity: StudioHomeActivity,
    route: StudioRoute,
    repository: StudioRepository,
    work: StudioWork,
    auxiliary: AuxiliaryPanel,
    studio: StudioPanels,
    agent: AgentPanel,
    refresh: () -> Unit,
    open: (StudioRoute) -> Unit,
): android.view.View? = when (route) {
    StudioRoute.Auxiliary("drafts") -> DraftPanel(activity, repository, work, refresh, open).view()
    StudioRoute.Auxiliary("proposals") -> ProposalPanel(activity, repository, work, refresh).view()
    is StudioRoute.ImageLinks -> studio.linkedImages(route.contactId, route.threadId)
    is StudioRoute.Settings -> auxiliary.settings()
    StudioRoute.Auxiliary("archive") -> auxiliary.archive()
    StudioRoute.Auxiliary("recycle") -> auxiliary.recycle()
    StudioRoute.Auxiliary("search") -> auxiliary.search()
    is StudioRoute.Auxiliary -> auxiliaryDetail(auxiliary, route.key)
    StudioRoute.Soul -> agent.soul()
    StudioRoute.Model -> agent.model()
    else -> null
}

private fun auxiliaryDetail(auxiliary: AuxiliaryPanel, key: String): android.view.View = when {
    key.startsWith("details:") -> auxiliary.details(key.substringAfter(':').toLongOrNull() ?: 0)
    key.startsWith("recycle:") -> auxiliary.recycledThread(key.substringAfter(':').toLongOrNull() ?: 0)
    else -> auxiliary.unsupported()
}

internal fun dailyCanvas(
    activity: StudioHomeActivity,
    tab: StudioTab,
    messages: MessagePanel,
    repository: StudioRepository,
    work: StudioWork,
    studio: StudioPanels,
    refresh: () -> Unit,
    open: (StudioRoute) -> Unit,
    openPhone: (String) -> Unit,
): android.view.View = when (tab) {
    StudioTab.SMS -> messages.messages()
    StudioTab.PHONE -> messages.phone()
    StudioTab.CONTACTS -> ContactPanel(activity, repository, work, refresh,
        openImages = { contactId -> open(StudioRoute.ImageLinks(contactId = contactId)) },
        openPhone = openPhone, editState = activity.contactEditState).view()
    StudioTab.STUDIO -> studio.albums()
    StudioTab.CALENDAR -> studio.calendar()
}
