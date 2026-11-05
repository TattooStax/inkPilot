package org.fossify.messages.inkpilot.ui

import org.fossify.messages.R

internal enum class StudioTab(val label: String, val viewId: Int, val icon: Int, val widthWeight: Float) {
    SMS("SMS", R.id.inkpilot_tab_sms, R.drawable.inkpilot_nav_sms, .75f),
    PHONE("Phone", R.id.inkpilot_tab_phone, R.drawable.inkpilot_nav_phone, .90f),
    CONTACTS("Contacts", R.id.inkpilot_tab_contacts, R.drawable.inkpilot_nav_contacts, 1.20f),
    STUDIO("Studio", R.id.inkpilot_tab_studio, R.drawable.inkpilot_nav_studio, .95f),
    CALENDAR("Calendar", R.id.inkpilot_tab_calendar, R.drawable.inkpilot_nav_calendar, 1.20f),
}
