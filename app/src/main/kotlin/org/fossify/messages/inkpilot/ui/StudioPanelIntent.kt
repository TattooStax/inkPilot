package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.content.Intent

/** Notification taps reuse the shell and leave its unsent per-tab composer intact. */
internal fun studioPanelIntent(context: Context, panel: String): Intent =
    Intent(context, StudioHomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("inkpilot_panel", panel)
