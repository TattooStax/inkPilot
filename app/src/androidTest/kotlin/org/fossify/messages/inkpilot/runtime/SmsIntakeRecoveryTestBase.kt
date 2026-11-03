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
        since = System.currentTimeMillis()
    }

    @After
    fun removeOnlyOwnedFixtures() {
        if (::repository.isInitialized) repository.close()
        if (::context.isInitialized) {
            for (uri in ownedRows) context.contentResolver.delete(uri, null, null)
            if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
        }
    }

    protected fun insertInbox(address: String, body: String, date: Long = since, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX): Long {
        val thread = Telephony.Threads.getOrCreateThreadId(context, "+12025550991")
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.THREAD_ID, thread); put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, type); put(Telephony.Sms.SUBSCRIPTION_ID, -1)
            put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values))
        ownedRows.add(uri)
        return ContentUris.parseId(uri)
    }

    protected fun nativeBody(id: Long): String? = context.contentResolver.query(
        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), arrayOf(Telephony.Sms.BODY), null, null, null,
    ).use { cursor -> if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null }
}
