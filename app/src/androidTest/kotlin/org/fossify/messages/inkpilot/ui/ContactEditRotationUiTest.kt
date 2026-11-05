package org.fossify.messages.inkpilot.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Unsaved staff notes remain editable after Activity recreation. */
@RunWith(AndroidJUnit4::class)
class ContactEditRotationUiTest {
    @Test fun recreationRestoresMemorySourceAndPauseWithoutCommittingThem() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!context.baseConfig.isAppPasswordProtectionOn && context.baseConfig.appPasswordHash.isEmpty())
        val priorUnlock = context.baseConfig.lastUnlockTimestampMs
        context.appLockManager.unlock()
        val name = "Rotation client ${UUID.randomUUID()}"
        val phone = "+1202555" + (1000..9999).random()
        StudioRepository(context).use { repository ->
            val contact = repository.upsertContact(phone, name)
            try {
                ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                    scenario.onActivity { it.openRoute(StudioRoute.Tab(StudioTab.CONTACTS)) }
                    SmokeViews.assertText(name)
                    scenario.onActivity { activity ->
                        assertTrue((findText(activity.window.decorView, name)?.parent as? View)
                            ?.performClick() == true)
                    }
                    SmokeViews.await("contact editor") {
                        nodeOrNull(context, "inkpilot_contact_memory")
                    }
                    val memory = "Draft consult details, not yet saved"
                    val source = "In-person consultation"
                    setText(node(context, "inkpilot_contact_memory"), memory)
                    setText(node(context, "inkpilot_contact_source"), source)
                    assertTrue(node(context, "inkpilot_contact_paused")
                        .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    assertTrue(node(context, "inkpilot_contact_paused").isChecked)

                    scenario.recreate()
                    SmokeViews.await("restored contact editor") {
                        nodeOrNull(context, "inkpilot_contact_memory")
                            ?.takeIf { it.text?.toString() == memory }
                    }
                    assertEquals(source, node(context, "inkpilot_contact_source").text?.toString())
                    assertTrue(node(context, "inkpilot_contact_paused").isChecked)
                    assertEquals("", repository.contact(contact.id)?.memory)
                    assertFalse(repository.contact(contact.id)?.paused ?: true)
                    SmokeViews.clickText("Cancel")
                    scenario.recreate()
                    SmokeViews.assertText(name)
                    assertNull(nodeOrNull(context, "inkpilot_contact_memory"))
                }
            } finally {
                repository.database.delete("contact_aliases", "contact_id = ?", arrayOf(contact.id))
                repository.database.delete("studio_contacts", "id = ?", arrayOf(contact.id))
                context.baseConfig.lastUnlockTimestampMs = priorUnlock
            }
        }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun node(context: Context, id: String): AccessibilityNodeInfo = requireNotNull(nodeOrNull(context, id)).also {
        it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
    }
    private fun nodeOrNull(context: Context, id: String): AccessibilityNodeInfo? =
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(context.packageName + ":id/" + id)
            ?.firstOrNull()

    private fun findText(view: View, value: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == value }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), value) }
        else -> null
    }
}
