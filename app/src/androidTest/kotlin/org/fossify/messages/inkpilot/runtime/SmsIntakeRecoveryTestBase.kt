package org.fossify.messages.inkpilot.runtime

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.inkpilot.data.StudioRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import java.util.UUID

abstract class SmsIntakeRecoveryTestBase {
    protected lateinit var context: Context
    protected lateinit var repository: StudioRepository
    protected var afterId = 0L
    protected var since = 0L
    private lateinit var databaseName: String
    private val ownedRows = mutableListOf<Uri>()
    private val ownedBodies = mutableSetOf<String>()

    @Before
    fun prepareProviderFixture() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Provider fixtures run only on an emulator." }
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val roles = context.getSystemService(RoleManager::class.java)
        assertTrue(roles.isRoleAvailable(RoleManager.ROLE_SMS))
        assertTrue("inkPilot must hold the real SMS role", roles.isRoleHeld(RoleManager.ROLE_SMS))
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS))
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC").use {
            checkNotNull(it)
            afterId = if (it.moveToFirst()) it.getLong(0) else 0
        }
        databaseName = "intake-recovery-test-${UUID.randomUUID()}.db"
        repository = StudioRepository(context, databaseName)
        // Automatic startup recovery scans only post-install inbox history. Keep this
        // isolated scan fixture earlier so it cannot populate the real studio database.
        @Suppress("DEPRECATION")
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        since = (installedAt - 10_000).coerceAtLeast(1)
    }

    @After
    fun removeOnlyOwnedFixtures() {
        if (::repository.isInitialized) repository.close()
        if (::context.isInitialized) {
            FilteredSmsJournal(context).use { journal ->
                for (uri in ownedRows) journal.admissions.removeAdmission(ContentUris.parseId(uri))
                var cursor: FilteredSmsJournal.Cursor? = null
                do {
                    val page = journal.page(cursor, limit = 100)
                    page.entries.filter { it.reason == "native_unadmitted" && it.body in ownedBodies }
                        .forEach { journal.remove(it.receipt) }
                    cursor = page.next
                } while (cursor != null)
            }
            for (uri in ownedRows) context.contentResolver.delete(uri, null, null)
            if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
        }
    }

    protected fun openFixtureRepository() = StudioRepository(context, databaseName)

    protected fun reopenRepository() {
        repository.close()
        repository = StudioRepository(context, databaseName)
    }

    protected fun insertInbox(address: String, body: String, date: Long = since,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX, admitted: Boolean = true): Long {
        val thread = Telephony.Threads.getOrCreateThreadId(context, "+12025550991")
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.THREAD_ID, thread); put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, type); put(Telephony.Sms.SUBSCRIPTION_ID, -1)
            put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values))
        ownedRows.add(uri)
        ownedBodies.add(body)
        val id = ContentUris.parseId(uri)
        if (admitted && type == Telephony.Sms.MESSAGE_TYPE_INBOX) FilteredSmsJournal(context).use {
            check(it.admissions.admitFromProvider(context, id,
                SmsAdmissionLedger.NativeIdentity(thread, address, body, date, -1))) {
                "Fixture was not admitted by native readback"
            }
        }
        return id
    }

    protected fun nativeBody(id: Long): String? = context.contentResolver.query(
        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), arrayOf(Telephony.Sms.BODY), null, null, null,
    ).use { cursor -> if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null }
}
