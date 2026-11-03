package org.fossify.messages.inkpilot.runtime

import android.content.Context
import org.fossify.messages.inkpilot.data.StudioRepository

/** Policy lives in the studio database so revision invalidation is transactional. */
class AgentSettings(private val context: Context) {
    private val preferences get() = context.getSharedPreferences("inkpilot-runtime", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = StudioRepository(context).use { it.isDraftingEnabled() }
        set(value) { StudioRepository(context).use { it.setDraftingEnabled(value) } }

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

    val status: String get() = preferences.getString("status", "Import a local model to get started.")!!

    internal fun recordModel(name: String) { preferences.edit().putString("model-name", name).commit() }

    internal fun recordStatus(message: String) { preferences.edit().putString("status", message).apply() }
}
