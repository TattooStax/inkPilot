package org.fossify.messages.inkpilot.smoke

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.draftsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Clean-install role and one-shell navigation contract on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class ShellBaselineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var fixtureUri: Uri? = null
    private var fixtureThread = 0L
    private val fixtureBody = "Synthetic shell baseline 20261104"

    @Before
    fun requireCleanDisposableEmulatorWithSmsRole() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        assertTrue("Caller must establish SMS role before launching the test",
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS).forEach {
            assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, it))
        }
        // Refuse preexisting content instead of clearing it. Never use customer data.
        listOf(Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI).forEach { uri ->
            context.contentResolver.query(uri, arrayOf("_id"), null, null, null).use {
                check(requireNotNull(it).count == 0) { "Use a clean disposable emulator provider" }
            }
        }
        check(context.messagesDB.getAll().isEmpty() && context.draftsDB.getAll().isEmpty()) {
            "Use a clean disposable emulator app database"
        }
    }

    @Test
    fun shellOwnsThreadAndNewMessageWithoutLaunchingInheritedActivities() {
        fixtureThread = Telephony.Threads.getOrCreateThreadId(context, "+12025550983")
        fixtureUri = requireNotNull(context.contentResolver.insert(Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.THREAD_ID, fixtureThread)
                put(Telephony.Sms.ADDRESS, "+12025550983")
                put(Telephony.Sms.BODY, fixtureBody)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.SUBSCRIPTION_ID, -1)
            }))
        val oldThread = instrumentation.addMonitor(ThreadActivity::class.java.name, null, true)
        val oldCompose = instrumentation.addMonitor(NewConversationActivity::class.java.name, null, true)
        try {
            ActivityScenario.launch(StudioHomeActivity::class.java).use { home ->
                SmokeViews.await<Boolean>("synthetic inbox row") {
                    var found = false
                    home.onActivity { found = findTextContaining(it.window.decorView, fixtureBody) != null }
                    if (found) true else null
                }
                home.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.inkpilot_home))
                    assertTrue(requireNotNull(findTextContaining(activity.window.decorView, fixtureBody)).performClick())
                }
                SmokeViews.assertText("Client reference images")
                SmokeViews.assertText(fixtureBody)
                home.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.inkpilot_home))
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                SmokeViews.clickText("New message")
                home.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.inkpilot_home))
                    assertNotNull(findHint(activity.window.decorView, "Recipients separated by |"))
                    assertNotNull(findHint(activity.window.decorView, "Message"))
                }
            }
            assertEquals("Thread stays inside inkPilot", 0, oldThread.hits)
            assertEquals("Compose stays inside inkPilot", 0, oldCompose.hits)
            assertTrue(context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
            File(context.filesDir, "device-test-evidence").apply { mkdirs() }
                .resolve("shell-baseline.txt").writeText(
                    "SMS role held; shell owns thread and compose; no send performed.\n")
        } finally {
            instrumentation.removeMonitor(oldThread)
            instrumentation.removeMonitor(oldCompose)
        }
    }

    @After
    fun removeOnlyOwnedSyntheticRows() {
        fixtureUri?.let {
            context.contentResolver.delete(it, null, null)
            context.messagesDB.delete(MessageKey(MessageOrigin.SMS, ContentUris.parseId(it)))
            context.draftsDB.delete(fixtureThread)
            context.conversationsDB.deleteThreadId(fixtureThread)
        }
    }

    private fun findTextContaining(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString().contains(text)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTextContaining(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun findHint(view: View, hint: String): EditText? {
        if (view is EditText && view.hint?.toString() == hint) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findHint(view.getChildAt(index), hint)?.let { return it }
        }
        return null
    }
}
