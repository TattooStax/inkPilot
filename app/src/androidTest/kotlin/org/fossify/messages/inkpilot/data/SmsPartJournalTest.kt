package org.fossify.messages.messaging

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SmsPartJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<String>()
    private fun name(): String = "sms-parts-test-${UUID.randomUUID()}.db".also(databases::add)
    private fun row(id: Long) = ContentUris.withAppendedId(Sms.CONTENT_URI, id)

    @After fun clean() { databases.forEach { context.deleteDatabase(it) } }

    @Test fun reorderedSuccessesNeverMarkNativeSentBeforeEveryPart() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90001), 3)!!
            var native = Sms.MESSAGE_TYPE_OUTBOX
            val writes = mutableListOf<Int>()
            fun settle(part: Int) = settleSmsPart(journal, SmsPartCallback.parse(claim.callbackUri(part))!!,
                true, { true }, { native = it; writes += it; true })
            assertEquals(SmsPartState.DISPATCHING, settle(2).state)
            assertEquals(SmsPartState.DISPATCHING, settle(0).state)
            assertTrue(writes.isEmpty())
            assertEquals(SmsPartState.SENT, settle(1).state)
            assertEquals(listOf(Sms.MESSAGE_TYPE_SENT), writes)
            assertEquals(SmsPartState.SENT, journal.state(claim.rowId, exactNativeType = { native }))
            assertFalse(journal.claim(row(90001), 3) != null)
        }
    }

    @Test fun earlyFailureThenSuccessIsUnknownAndNeverSent() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90002), 2)!!
            val writes = mutableListOf<Int>()
            fun settle(part: Int, success: Boolean) = settleSmsPart(journal,
                SmsPartCallback.parse(claim.callbackUri(part))!!, success, { true }, { writes += it; true })
            assertEquals(SmsPartState.PARTIAL_FAILED, settle(1, false).state)
            assertEquals(SmsPartState.UNKNOWN, settle(0, true).state)
            assertEquals(SmsPartState.UNKNOWN, settle(0, true).state)
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId))
            assertTrue(writes.isNotEmpty())
            assertTrue(writes.all { it == Sms.MESSAGE_TYPE_FAILED })
        }
    }

    @Test fun allFailedPartsCanSettleFailed() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90003), 2)!!
            val writes = mutableListOf<Int>()
            fun fail(part: Int) = settleSmsPart(journal, SmsPartCallback.parse(claim.callbackUri(part))!!,
                false, { true }, { writes += it; true })
            assertEquals(SmsPartState.PARTIAL_FAILED, fail(1).state)
            assertEquals(SmsPartState.FAILED, fail(0).state)
            assertEquals(SmsPartState.FAILED, journal.state(claim.rowId))
            assertEquals(2, writes.size)
            assertTrue(writes.all { it == Sms.MESSAGE_TYPE_FAILED })
        }
    }

    @Test fun duplicateAndContradictoryCallbackCannotRestoreSent() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90004), 2)!!
            val writes = mutableListOf<Int>()
            fun settle(part: Int, success: Boolean) = settleSmsPart(journal,
                SmsPartCallback.parse(claim.callbackUri(part))!!, success, { true }, { writes += it; true })
            settle(1, true)
            assertEquals(SmsPartState.DISPATCHING, settle(1, true).state)
            assertEquals(SmsPartState.SENT, settle(0, true).state)
            assertEquals(SmsPartState.SENT, settle(0, true).state)
            assertEquals(SmsPartState.UNKNOWN, settle(0, false).state)
            assertEquals(SmsPartState.UNKNOWN, settle(1, true).state)
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId))
            assertEquals(Sms.MESSAGE_TYPE_SENT, writes.first())
            assertEquals(Sms.MESSAGE_TYPE_FAILED, writes.last())
        }
    }

    @Test fun missingCallbackExpiresToUnknownAndLateSuccessCannotRestoreSent() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90005), 2)!!
            val callback = SmsPartCallback.parse(claim.callbackUri(0))!!
            settleSmsPart(journal, callback, true, { true }, { true })
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId,
                now = System.currentTimeMillis() + 11 * 60 * 1000L))
            assertEquals(SmsPartState.UNKNOWN, settleSmsPart(journal,
                SmsPartCallback.parse(claim.callbackUri(1))!!, true, { true },
                { it == Sms.MESSAGE_TYPE_FAILED }).state)
        }
    }

    @Test fun restartReconcilesOnlyVerifiedTerminalNativeType() {
        val database = name()
        val claim = SmsPartJournal(context, database).use { journal ->
            journal.claim(row(90006), 1)!!.also {
                assertEquals(SmsPartState.READY_SENT, journal.record(
                    SmsPartCallback.parse(it.callbackUri(0))!!, true))
            }
        }
        SmsPartJournal(context, database).use { reopened ->
            assertEquals(SmsPartState.READY_SENT, reopened.state(claim.rowId,
                exactNativeType = { Sms.MESSAGE_TYPE_OUTBOX }))
            assertEquals(SmsPartState.SENT, reopened.state(claim.rowId,
                exactNativeType = { Sms.MESSAGE_TYPE_SENT }))
            assertNull(reopened.claim(row(90006), 1))
        }
    }

    @Test fun unverifiedRowAndProviderFailureStayUnknown() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90007), 1)!!
            val callback = SmsPartCallback.parse(claim.callbackUri(0))!!
            assertFalse(settleSmsPart(journal, callback, true, { true }, { false }).verified)
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId))
        }
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90008), 1)!!
            val callback = SmsPartCallback.parse(claim.callbackUri(0))!!
            assertFalse(settleSmsPart(journal, callback, true, { false },
                { error("native row absent") }).verified)
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId))
        }
    }

    @Test fun duplicateCannotPromoteProviderDriftBackToSent() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90011), 1)!!
            val callback = SmsPartCallback.parse(claim.callbackUri(0))!!
            var native = Sms.MESSAGE_TYPE_OUTBOX
            fun success() = settleSmsPart(journal, callback, true, { true }, { native = it; true })
            assertEquals(SmsPartState.SENT, success().state)
            native = Sms.MESSAGE_TYPE_FAILED
            assertTrue(success().verified) // A duplicate callback itself never rewrites a terminal row.
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId, exactNativeType = { native }))
            assertEquals(Sms.MESSAGE_TYPE_FAILED, native)
        }
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90012), 1)!!
            val callback = SmsPartCallback.parse(claim.callbackUri(0))!!
            var native = Sms.MESSAGE_TYPE_OUTBOX
            fun failure() = settleSmsPart(journal, callback, false, { true }, { native = it; true })
            assertEquals(SmsPartState.FAILED, failure().state)
            native = Sms.MESSAGE_TYPE_SENT
            assertTrue(failure().verified)
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId, exactNativeType = { native }))
        }
    }

    @Test fun handoffExceptionDemotesEvenAnEarlyTerminalCallback() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90009), 1)!!
            val callback = SmsPartCallback.parse(claim.callbackUri(0))!!
            assertEquals(SmsPartState.SENT, settleSmsPart(journal,
                callback, true, { true }, { true }).state)
            journal.unknownRow(row(claim.rowId))
            assertEquals(SmsPartState.UNKNOWN, journal.state(claim.rowId))
            assertNull(journal.claim(row(90009), 1))
        }
    }

    @Test fun callbackIdentityRequiresExactAttemptPartAndCanonicalNativeId() {
        SmsPartJournal(context, name()).use { journal ->
            val claim = journal.claim(row(90010), 2)!!
            assertNull(SmsPartCallback.parse(row(90010)))
            assertNull(SmsPartCallback.parse(claim.callbackUri(2).buildUpon()
                .appendQueryParameter("extra", "x").build()))
            assertNull(SmsPartCallback.parse(claim.callbackUri(0).buildUpon()
                .appendQueryParameter("part", "1").build()))
            val wrong = SmsPartCallback(claim.rowId, UUID.randomUUID().toString(), 0)
            assertNull(journal.record(wrong, true))
            val beyond = SmsPartCallback(claim.rowId, claim.attempt, 2)
            assertNull(journal.record(beyond, true))
            assertEquals(SmsPartState.DISPATCHING, journal.state(claim.rowId))
        }
    }
}
