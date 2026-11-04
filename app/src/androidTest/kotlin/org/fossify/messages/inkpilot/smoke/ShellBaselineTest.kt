package org.fossify.messages.inkpilot.smoke

import android.Manifest
import android.app.Activity
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
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.draftsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Passing characterization of inherited transitions; Tasks 9–11 replace this contract. */
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
    fun shellCurrentlyHandsThreadAndNewMessageToInheritedActivities() {
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
        ActivityScenario.launch(StudioHomeActivity::class.java).use { home ->
            SmokeViews.assertText(fixtureBody)
            home.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.inkpilot_home))
                val text = requireNotNull(findText(activity.window.decorView, fixtureBody))
                var row: View = text
                while (!row.isClickable) row = row.parent as View
                assertTrue(row.performClick())
            }
            val thread = awaitResumed(ThreadActivity::class.java)
            instrumentation.runOnMainSync {
                assertEquals(fixtureThread, thread.intent.getLongExtra(THREAD_ID, -1))
                assertNull("Baseline: inherited thread has no shell footer",
                    thread.findViewById<View>(R.id.inkpilot_home))
                thread.finish()
            }
            awaitResumed(StudioHomeActivity::class.java)
            SmokeViews.clickText("New message")
            val compose = awaitResumed(NewConversationActivity::class.java)
            instrumentation.runOnMainSync {
                assertNull("Baseline: inherited compose has no shell footer",
                    compose.findViewById<View>(R.id.inkpilot_home))
                compose.finish()
            }
            awaitResumed(StudioHomeActivity::class.java)
        }
        assertTrue(context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        File(context.filesDir, "device-test-evidence").apply { mkdirs() }
            .resolve("shell-baseline.txt").writeText(
                "SMS role held; shell -> ThreadActivity; shell -> NewConversationActivity; no send performed.\n")
    }

    @After
    fun removeOnlyOwnedSyntheticRows() {
        fixtureUri?.let {
            context.contentResolver.delete(it, null, null)
            context.messagesDB.delete(ContentUris.parseId(it))
            context.draftsDB.delete(fixtureThread)
            context.conversationsDB.deleteThreadId(fixtureThread)
        }
    }

    private fun <T : Activity> awaitResumed(type: Class<T>): T = SmokeViews.await(type.simpleName) {
        var activity: T? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { type.isInstance(it) }?.let(type::cast)
        }
        activity
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findText(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }
}
