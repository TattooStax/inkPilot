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
import android.os.SystemClock
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkstax.sms.transport.rolePostureForDedicatedBusinessPhone
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.draftsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Draft
import org.fossify.messages.models.Message
import org.fossify.messages.models.RecycleBinMessage
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
        assertEquals(1, context.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, providerThread),
            ContentValues().apply { put(Telephony.Threads.ARCHIVED, 1) }, null, null))
        val scheduled = message(scheduledId, temporaryThread, "Human scheduled fixture",
            ((now + 86_400_000) / 1000).toInt(), scheduled = true)
        val recycled = message(recycledId, providerThread, "Recycled fixture", (now / 1000).toInt())
        val draft = Draft(temporaryThread, "Unsent human draft", now)
        val recycle = RecycleBinMessage(recycledId, now)
        context.messagesDB.insertMessages(scheduled, recycled)
        context.messagesDB.insertRecycleBinEntry(recycle)
        context.draftsDB.insertOrUpdate(draft)
        context.conversationsDB.insertOrUpdate(conversation(providerThread, "+12025550981",
            (now / 1000).toInt(), archived = true))
        context.conversationsDB.insertOrUpdate(conversation(temporaryThread, "+12025550982",
            scheduled.date, scheduled = true))
        // Use the literal retired preference so the regression still covers old installs
        // after its production accessor and constant are removed.
        assertTrue(context.getSharedPrefs().edit().putBoolean("was_db_cleared_4", false).commit())
        context.config.useRecycleBin = true
        context.config.appRunCount = 0
        context.storeRolePosture(rolePostureForDedicatedBusinessPhone(true))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        assertTrue(context.packageManager.getActivityInfo(requireNotNull(intent.component), 0).exported)
        ActivityScenario.launch<MainActivity>(intent).use {
            // This row was never seeded into Room: its appearance is the completion
            // signal for getNewConversations' first-run provider refresh, not just onResume.
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (context.messagesDB.getAll().none { it.id == providerId && !it.isScheduled } &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue("Startup must import the native SMS projection",
                context.messagesDB.getAll().any {
                    it.id == providerId && !it.isMMS && !it.isScheduled &&
                        it.body == "Synthetic native history"
                })
            assertEquals("First inherited launch must preserve the human scheduled message",
                scheduled, context.messagesDB.getAll().find { it.id == scheduledId })
            assertEquals(draft, context.draftsDB.getDraftById(temporaryThread))
            assertTrue("Archived native conversation must remain archived",
                context.conversationsDB.getConversationWithThreadId(providerThread)?.isArchived == true)
            assertEquals(listOf(recycled), context.messagesDB.getThreadMessagesFromRecycleBin(providerThread))
            assertEquals(1, context.messagesDB.getArchivedCount())
            assertEquals(listOf(recycled), context.messagesDB.getOldRecycleBinMessages(recycle.deletedTS + 1))
            context.contentResolver.query(requireNotNull(providerUri),
                arrayOf(Telephony.Sms.BODY), null, null, null).use { cursor ->
                assertTrue(requireNotNull(cursor).moveToFirst())
                assertEquals("Synthetic native history", cursor.getString(0))
            }
        }
    }

    @After
    fun removeOnlyOwnedFixtures() {
        if (!fixtureStarted) return
        providerUri?.let { context.contentResolver.delete(it, null, null) }
        providerUri?.let { context.messagesDB.delete(ContentUris.parseId(it)) }
        context.messagesDB.delete(scheduledId)
        context.messagesDB.delete(recycledId)
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
