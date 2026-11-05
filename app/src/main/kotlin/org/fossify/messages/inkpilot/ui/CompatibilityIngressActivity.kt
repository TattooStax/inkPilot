package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** Retains historical component names without ever inflating a legacy messenger screen. */
class CompatibilityIngressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        startActivity(ExternalRouteParser.forward(this, intent))
        finish()
    }
}
