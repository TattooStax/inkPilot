package org.fossify.messages.inkpilot.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.fossify.messages.inkpilot.ui.ExternalRouteParser
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.StudioTab
import org.fossify.messages.inkpilot.ui.encode

/** ACTION_DIAL fills the Phone tab; opening a link never places a call. */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); forward(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); forward(intent) }

    private fun forward(source: Intent) {
        val number = source.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        val route = if (number.isBlank()) StudioRoute.Tab(StudioTab.PHONE) else StudioRoute.PhoneDial(number)
        startActivity(Intent(this, StudioHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ExternalRouteParser.ROUTE, route.encode()))
        finish()
    }
}
