package org.fossify.messages.inkpilot.phone
import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** ACTION_DIAL fills a number; opening a link never places a call. */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (org.fossify.messages.inkpilot.ui.StudioAppLock(this).onResume()) render() }
    override fun onResume() { super.onResume(); if (org.fossify.messages.inkpilot.ui.StudioAppLock(this).onResume()) render() }
    override fun onPause() { setContentView(android.widget.TextView(this)); super.onPause() }
    private fun render() {
        setContentView(PhonePanel(this).view(intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()))
    }
}
