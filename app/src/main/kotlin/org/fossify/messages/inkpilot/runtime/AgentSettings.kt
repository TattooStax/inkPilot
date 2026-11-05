package org.fossify.messages.inkpilot.runtime

import android.content.Context
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.quarantineUnqualifiedDrafting

/** Policy lives in the studio database so revision invalidation is transactional. */
class AgentSettings(private val context: Context) {
    private val preferences get() = context.getSharedPreferences("inkpilot-runtime", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = StudioRepository(context).use {
            if (AgentRuntime.BOUNDED_INTENT_QUALIFIED) it.isDraftingEnabled()
            else {
                if (it.quarantineUnqualifiedDrafting()) recordStatus(UNQUALIFIED_STATUS)
                false
            }
        }
        set(value) {
            StudioRepository(context).use {
                if (value && !AgentRuntime.BOUNDED_INTENT_QUALIFIED) {
                    it.quarantineUnqualifiedDrafting()
                    recordStatus(UNQUALIFIED_STATUS)
                } else it.setDraftingEnabled(value)
            }
        }

    var soul: String
        get() = StudioRepository(context).use { it.soul() }
        set(value) {
            require(value.length <= 4_000) { "Keep the studio guide under 4,000 characters." }
            StudioRepository(context).use { it.setSoul(value.trim()) }
        }

    val modelName: String?
        get() = if (ModelFiles.active(context).isFile) {
            preferences.getString("model-name", "Local GGUF model")
        } else null

    val status: String get() = if (AgentRuntime.BOUNDED_INTENT_QUALIFIED)
        preferences.getString("status", "Import a local model to get started.")!! else UNQUALIFIED_STATUS

    internal fun recordModel(name: String) { preferences.edit().putString("model-name", name).commit() }

    internal fun recordStatus(message: String) { preferences.edit().putString("status", message).apply() }

    private companion object {
        const val UNQUALIFIED_STATUS = "Model qualification is pending. Staff should reply directly."
    }
}
