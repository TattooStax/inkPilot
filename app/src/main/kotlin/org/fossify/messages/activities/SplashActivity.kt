package org.fossify.messages.activities

import android.content.Intent
import org.fossify.commons.activities.BaseSplashActivity

class SplashActivity : BaseSplashActivity() {
    override fun initActivity() {
        startActivity(Intent(this, org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java))
        finish()
    }
}
