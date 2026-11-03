package org.fossify.messages.inkpilot.runtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.inkpilot.data.StudioRepository

class StudioSmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val draftId = intent.getStringExtra("draft_id") ?: return
        val part = intent.getIntExtra("part", -1)
        if (part !in 0..31) return
        val code = resultCode
        val pending = goAsync()
        ensureBackgroundThread {
            try {
                StudioRepository(context).use { repository ->
                    val state = repository.recordSendPart(draftId, part, code == Activity.RESULT_OK, if (code == Activity.RESULT_OK) null else "Carrier result $code")
                    val claim = repository.getSendClaim(draftId)
                    if (state != null && claim != null) SmsProviderHistory.finish(context, claim, state)
                }
            } catch (_: Exception) {
                AgentSettings(context).recordStatus("A send receipt could not be saved. Check the conversation before sending again.")
            } finally {
                pending.finish()
            }
        }
    }
}
