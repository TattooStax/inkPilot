package org.fossify.messages.inkpilot.runtime

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.helpers.SmsRole
import org.fossify.messages.helpers.REPLY
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.receivers.DirectReplyReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectReplyPreservationTest {
    @Test fun missingSmsRoleKeepsExactHumanReplyForStaff() {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val noRole = object : ContextWrapper(real) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.ROLE_SERVICE) null else super.getSystemService(name)
        }
        assertFalse(SmsRole.isHeld(noRole))
        val store = DirectReplyPendingStore(real)
        val prior = store.held().map { it.id }.toSet()
        val body = "Keep this reference for Saturday"
        val intent = Intent(real, DirectReplyReceiver::class.java).apply {
            putExtra(THREAD_NUMBER, "+12025550188")
            putExtra(THREAD_ID, 73L)
            putExtra(DirectReplyReceiver.SUBSCRIPTION_ID, 7)
        }
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(REPLY).build()), intent,
            Bundle().apply { putCharSequence(REPLY, body) })
        DirectReplyReceiver().onReceive(noRole, intent)
        val held = store.held().single { it.id !in prior }
        try {
            assertEquals(73L, held.threadId)
            assertEquals("+12025550188", held.recipient)
            assertEquals(body, held.body)
            assertEquals(7, held.subscriptionId)
        } finally { store.complete(held.id) }
    }

    @Test fun missingThreadIdentityKeepsExactHumanReplyForStaff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DirectReplyPendingStore(context)
        val prior = store.held().map { it.id }.toSet()
        val body = "Résumé reference, please"
        val intent = Intent(context, DirectReplyReceiver::class.java).apply {
            putExtra(THREAD_NUMBER, "+12025550188")
            putExtra(THREAD_ID, 0L)
            putExtra(DirectReplyReceiver.SUBSCRIPTION_ID, 7)
        }
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(REPLY).build()), intent,
            Bundle().apply { putCharSequence(REPLY, body) })
        DirectReplyReceiver().onReceive(context, intent)
        val held = store.held().single { it.id !in prior }
        try {
            assertNull(held.threadId)
            assertEquals("+12025550188", held.recipient)
            assertEquals(body, held.body)
            assertEquals(7, held.subscriptionId)
        } finally { store.complete(held.id) }
    }
}
