package org.fossify.messages.inkpilot.data

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkstax.sms.transport.rolePostureForDedicatedBusinessPhone
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.draftsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Draft
import org.fossify.messages.models.Message
import org.fossify.messages.models.RecycleBinMessage
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.messaging.key
import org.fossify.messages.inkpilot.smoke.SmokeViews
import org.fossify.messages.inkpilot.ui.ConversationGateway
import org.fossify.messages.inkpilot.ui.StudioHomeActivity
import org.fossify.messages.inkpilot.ui.StudioRoute
import org.fossify.messages.inkpilot.ui.StudioMessageRows
import org.fossify.messages.transport.storeRolePosture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real startup database and provider, only on a clean disposable emulator install. */
@RunWith(AndroidJUnit4::class)
class LegacyFirstLaunchTest {
    private lateinit var context: Context
    private var providerUri: Uri? = null
    private var providerThread = 0L
    private val scheduledId = 8_000_001L
    private val recycledId = 8_000_002L
    private val temporaryThread = 8_000_003L
    private var fixtureStarted = false

    @Before
    fun requireIsolatedEmulator() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Legacy first-launch fixture runs only on an emulator."
        }
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("The emulator app must hold SMS role",
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS).forEach {
            assertEquals("Grant fixture permission $it", PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, it))
        }
        // A clean app database does not imply a clean native provider. Refuse
        // reused emulator history (including empty/archived threads) before
        // getOrCreateThreadId, archive updates, or any other fixture mutation.
        listOf(Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI,
            Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        ).forEach { uri ->
            context.contentResolver.query(uri, arrayOf("_id"), null, null, null).use { cursor ->
                checkNotNull(cursor) { "Cannot verify disposable emulator provider isolation: $uri" }
                check(!cursor.moveToFirst()) {
                    "Use an empty disposable SMS/MMS provider; existing native history must never be cleared or modified."
                }
            }
        }
        check(context.messagesDB.getAll().isEmpty() &&
            context.draftsDB.getAll().isEmpty() &&
            context.messagesDB.getArchivedCount() == 0 &&
            context.conversationsDB.getNonArchived().isEmpty() &&
            context.conversationsDB.getAllArchived().isEmpty()) {
            "Use a clean disposable emulator app install; never clear an existing user's database."
        }
    }

    @Test
    fun preservesScheduledDraftArchiveAndRecycleOnLegacyFirstLaunch() {
        fixtureStarted = true
        val now = System.currentTimeMillis()
        providerThread = Telephony.Threads.getOrCreateThreadId(context, "+12025550981")
        val values = ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, providerThread)
            put(Telephony.Sms.ADDRESS, "+12025550981")
            put(Telephony.Sms.BODY, "Synthetic native history")
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.SUBSCRIPTION_ID, -1)
        }
        providerUri = requireNotNull(context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values))
        val providerId = ContentUris.parseId(requireNotNull(providerUri))
        // Empty providers can retain high autoincrement sequences after cleanup.
        // Refuse namespace collisions before inserting any local fixture rows.
        check(providerId != scheduledId && providerId != recycledId && providerThread != temporaryThread) {
            "Disposable provider IDs collide with local fixture IDs."
        }
        val scheduled = message(scheduledId, temporaryThread, "Human scheduled fixture",
            ((now + 86_400_000) / 1000).toInt(), scheduled = true)
        val recycled = message(recycledId, providerThread, "Recycled fixture", (now / 1000).toInt())
        val draft = Draft(temporaryThread, "Unsent human draft", now)
        val recycle = RecycleBinMessage(recycled.key(), now)
        context.messagesDB.insertMessages(scheduled, recycled)
        context.messagesDB.insertRecycleBinEntry(recycle)
        context.draftsDB.insertOrUpdate(draft)
        context.conversationsDB.insertOrUpdate(conversation(providerThread, "+12025550981",
            (now / 1000).toInt()))
        // Archive is human state in Room. The temporary scheduled thread is
        // local-only; Android providers need not support an archived column.
        context.conversationsDB.insertOrUpdate(conversation(temporaryThread, "+12025550982",
            scheduled.date, scheduled = true, archived = true))
        // Use the literal retired preference so the regression still covers old installs
        // after its production accessor and constant are removed.
        assertTrue(context.getSharedPrefs().edit().putBoolean("was_db_cleared_4", false).commit())
        context.config.useRecycleBin = true
        context.config.appRunCount = 0
        context.storeRolePosture(rolePostureForDedicatedBusinessPhone(true))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        assertTrue(context.packageManager.getActivityInfo(requireNotNull(intent.component), 0).exported)
        // The one-shell inbox reads Telephony directly; the compatibility alias
        // no longer runs the inherited MainActivity's Room cache import.
        val gateway = ConversationGateway(context)
        assertTrue("Native conversation must be pageable", gateway.pageThreads(null, 40).items.any {
            it.threadId == providerThread
        })
        assertTrue("Native SMS must be pageable with its provider identity",
            gateway.pageMessages(providerThread, null, 40).items.any {
                it.id == providerId && !it.isMMS && !it.isScheduled &&
                    it.body == "Synthetic native history"
            })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shellMonitor = instrumentation.addMonitor(StudioHomeActivity::class.java.name, null, false)
        var shell: StudioHomeActivity? = null
        try {
            context.startActivity(intent)
            shell = shellMonitor.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            val home = requireNotNull(shell) { "Historical MainActivity alias must open the inkPilot shell" }
            SmokeViews.await<Boolean>("native conversation in the one-shell inbox") {
                var visible = false
                instrumentation.runOnMainSync {
                    val list = home.findViewById<RecyclerView>(R.id.inkpilot_message_list)
                    visible = list != null && (0 until (list.adapter?.itemCount ?: 0)).any {
                        list.adapter!!.getItemId(it) == providerThread
                    }
                }
                visible.takeIf { it }
            }
            instrumentation.runOnMainSync {
                assertNotNull(home.findViewById<View>(R.id.inkpilot_home))
                home.openRoute(StudioRoute.Thread(providerThread))
            }
            SmokeViews.assertText("Client reference images")
            SmokeViews.await<Boolean>("native message loaded in the in-shell thread") {
                var ready = false
                instrumentation.runOnMainSync {
                    val list = home.findViewById<RecyclerView>(R.id.inkpilot_thread_message_list)
                    val position = (list?.adapter as? StudioMessageRows)?.messagePosition(
                        MessageKey(MessageOrigin.SMS, providerId)) ?: -1
                    if (position >= 0 && list.height > 0) {
                        list.scrollToPosition(position)
                        ready = true
                    }
                }
                ready.takeIf { it }
            }
            SmokeViews.assertText("Synthetic native history")
            assertEquals("First shell launch must preserve the human scheduled message",
                scheduled, context.messagesDB.getAll().find { it.id == scheduledId })
            assertEquals(draft, context.draftsDB.getDraftById(temporaryThread))
            assertTrue("Archived local conversation must remain archived",
                context.conversationsDB.getConversationWithThreadId(temporaryThread)?.isArchived == true)
            assertEquals(listOf(recycled), context.messagesDB.getThreadMessagesFromRecycleBin(providerThread))
            assertEquals(1, context.messagesDB.getArchivedCount())
            assertEquals(listOf(recycled), context.messagesDB.getOldRecycleBinMessages(recycle.deletedTS + 1))
            context.contentResolver.query(requireNotNull(providerUri),
                arrayOf(Telephony.Sms.BODY), null, null, null).use { cursor ->
                assertTrue(requireNotNull(cursor).moveToFirst())
                assertEquals("Synthetic native history", cursor.getString(0))
            }
        } finally {
            instrumentation.runOnMainSync { shell?.finish() }
            instrumentation.removeMonitor(shellMonitor)
        }
    }

    @After
    fun removeOnlyOwnedFixtures() {
        if (!fixtureStarted) return
        providerUri?.let { context.contentResolver.delete(it, null, null) }
        providerUri?.let { context.messagesDB.delete(MessageKey(MessageOrigin.SMS, ContentUris.parseId(it))) }
        context.messagesDB.delete(MessageKey(MessageOrigin.SCHEDULED_SMS, scheduledId))
        context.messagesDB.delete(MessageKey(MessageOrigin.SMS, recycledId))
        context.draftsDB.delete(temporaryThread)
        context.conversationsDB.deleteThreadId(temporaryThread)
        context.conversationsDB.deleteThreadId(providerThread)
    }

    private fun message(id: Long, thread: Long, body: String, date: Int, scheduled: Boolean = false) =
        Message(id, body, Telephony.Sms.MESSAGE_TYPE_SENT, -1, arrayListOf(), date, true,
            thread, false, null, "", "", "", -1, scheduled)

    private fun conversation(thread: Long, number: String, date: Int,
        scheduled: Boolean = false, archived: Boolean = false) =
        Conversation(thread, "Synthetic fixture", date, true, number, "", false, number,
            isScheduled = scheduled, isArchived = archived)
}
