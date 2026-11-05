package org.fossify.messages.inkpilot.smoke

import android.net.Uri
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.fossify.messages.messaging.*
import org.fossify.messages.extensions.config
import org.fossify.messages.inkpilot.ui.encode
import org.fossify.messages.inkpilot.ui.decodeRoute
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Synthetic dispatcher only: this suite never reaches a carrier. */
class InShellComposerTest {
    @Test fun missingSmsRoleRefusesBeforeSimLookupOrComposerClaim() {
        androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = ComposerStore(activity)
                val id = -81029L
                store.clear(id)
                try {
                    val request = OutgoingMessage(id, "+15550101012", "Human reply", emptyList(), 1)
                    val outcome = org.fossify.messages.inkpilot.ui.prepareInShellComposerSend(
                        activity, store, request, smsRoleHeld = { false },
                        activeSimIds = { error("SIM lookup must not occur without SMS role") }).getOrThrow()
                    assertEquals(DispatchOutcome.Rejected("Set inkPilot as the default SMS app before sending."), outcome)
                    assertNull(store.load(id))
                    assertFalse(store.isUnknown(id))
                    assertTrue(store.save(request.copy(body = "Still editable")))
                } finally { store.clear(id) }
            }
        }
    }
    @Test fun invalidPngsAreRefusedBeforeSimLookupOrComposerClaim() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs()
        val corrupt = "not a PNG image".toByteArray()
        source.writeBytes(corrupt)
        val valid = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            finally { bitmap.recycle() }
            output.toByteArray()
        }
        val validSource = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png").apply { writeBytes(valid) }
        val truncatedSource = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
            .apply { writeBytes(valid.copyOf(8)) }
        val oversizedSource = File(context.cacheDir, "attachments/${UUID.randomUUID()}.png")
            .apply { writeBytes(ByteArray(1_048_577)) }
        val validUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", validSource)
        val truncatedUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", truncatedSource)
        val oversizedUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", oversizedSource)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
        val id = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        try {
            androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java)
                .use { scenario -> scenario.onActivity { activity ->
                    val store = ComposerStore(activity)
                    val request = OutgoingMessage(id, "+15550101012", "Keep caption", listOf(uri), 1)
                    val result = org.fossify.messages.inkpilot.ui.prepareInShellComposerSend(
                        activity, store, request, activeSimIds = { error("SIM lookup must not occur") }).getOrThrow()
                    assertEquals(DispatchOutcome.Rejected("Image cannot be decoded"), result)
                    assertNull(store.load(id))
                    assertFalse(store.isUnknown(id))
                    fun assertPreclaimRefusal(candidate: Uri, expected: String) {
                        val rejected = org.fossify.messages.inkpilot.ui.prepareInShellComposerSend(activity, store,
                            request.copy(attachmentUris = listOf(candidate)), activeSimIds = {
                                error("SIM lookup must not occur") }).getOrThrow()
                        assertEquals(DispatchOutcome.Rejected(expected), rejected)
                        assertNull(store.load(id))
                        assertFalse(store.isUnknown(id))
                    }
                    assertPreclaimRefusal(truncatedUri, "Image cannot be decoded")
                    assertPreclaimRefusal(oversizedUri, "Image exceeds the 1 MiB MMS limit")
                    assertNull(activity.inShellMmsRejection(listOf(validUri)))
                    assertNull(store.load(id))
                } }
        } finally {
            ComposerStore(context).clear(id)
            listOf(source, validSource, truncatedSource, oversizedSource).forEach(File::delete)
        }
    }

    @Test fun groupSmsIsVisibleRefusalBeforeSimOrComposerClaim() {
        androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val previous = activity.config.sendGroupMessageMMS
                val store = ComposerStore(activity)
                val id = -81027L
                store.clear(id)
                try {
                    activity.config.sendGroupMessageMMS = false
                    val request = OutgoingMessage(id, "+15550101012|+15550101013", "Group text", emptyList(), 1)
                    val outcome = org.fossify.messages.inkpilot.ui.prepareInShellComposerSend(
                        activity, store, request, activeSimIds = { error("SIM lookup must not occur") }).getOrThrow()
                    assertEquals(DispatchOutcome.Rejected(SMS_GROUP_REFUSAL), outcome)
                    assertNull(store.load(id))
                } finally { activity.config.sendGroupMessageMMS = previous; store.clear(id) }
            }
        }
    }
    @Test fun composeKeepsShellAndOriginalTextOnRotation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val route = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550101012"), "retained text", listOf(Uri.parse("content://synthetic/retained")))
        val store = ComposerStore(context)
        store.clear(route.composerId)
        try {
            androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openRoute(route)
                    assertNotNull(activity.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_navigation))
                    fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
                    val editor = children(activity.window.decorView).filterIsInstance<android.widget.EditText>().first { it.hint == "Message" }
                    editor.setText("staff edit preserved")
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_home))
                    assertEquals("staff edit preserved", ComposerStore(activity).load(route.composerId)?.body)
                    assertEquals(listOf(Uri.parse("content://synthetic/retained")), ComposerStore(activity).load(route.composerId)?.attachmentUris)
                }
            }
        } finally { store.clear(route.composerId) }
    }

    @Test fun failedShareStaysVisibleAndCannotSendUntilAttachmentRemoved() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val failed = Uri.parse("content://synthetic/expired-share")
        val route = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(
            emptyList(), "Client caption preserved", listOf(failed), shareCopyFailed = true)
        assertEquals(route, route.encode().decodeRoute())
        val store = ComposerStore(context)
        store.clear(route.composerId)
        try {
            androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
                        else emptyList()
                    activity.openRoute(route)
                    val before = children(activity.window.decorView).filterIsInstance<android.widget.TextView>()
                    assertTrue(before.any { it.text.toString().contains("Remove it and attach again") })
                    assertFalse(before.single { it.text.toString() == "Send message" }.isEnabled)
                    assertEquals(listOf(failed), store.load(route.composerId)?.attachmentUris)
                    before.single { it.text.toString().startsWith("Remove attachment:") }.performClick()
                    val after = children(activity.window.decorView).filterIsInstance<android.widget.TextView>()
                    assertTrue(after.single { it.text.toString() == "Send message" }.isEnabled)
                    assertEquals("Client caption preserved", store.load(route.composerId)?.body)
                    assertEquals(emptyList<Uri>(), store.load(route.composerId)?.attachmentUris)
                }
            }
        } finally { store.clear(route.composerId) }
    }

    @Test fun failedMmsRetainsOriginalAcrossStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ComposerStore(context)
        val id = -81010L
        store.clear(id)
        val request = OutgoingMessage(id, "+15550101010", "original human text", listOf(Uri.parse("content://synthetic/image")), 12)
        try {
            val outcome = store.submit(request, MessageDispatcher({ false }, { true }) { fail("Unreadable MMS must not hand off"); DispatchOutcome.Accepted() })
            assertTrue(outcome is DispatchOutcome.Rejected)
            assertEquals(request, ComposerStore(context).load(id))
        } finally { store.clear(id) }
    }
    @Test fun unknownDoesNotRetryOrDowngradeToText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -81011L
        val store = ComposerStore(context)
        store.clear(id)
        val request = OutgoingMessage(id, "+15550101011", "caption", listOf(Uri.parse("content://synthetic/image")), 13)
        try {
            var calls = 0
            val dispatcher = MessageDispatcher({ true }, { true }) { calls++; DispatchOutcome.Unknown("claim") }
            assertTrue(store.submit(request, dispatcher) is DispatchOutcome.Unknown)
            assertEquals(request, ComposerStore(context).load(id))
            assertTrue(ComposerStore(context).submit(request, dispatcher) is DispatchOutcome.Rejected)
            assertEquals(1, calls)
        } finally { store.clear(id) }
    }
    @Test fun distinctComposeRoutesNeverRestoreAnotherRecipientOrMedia() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550101111"), "A private caption", listOf(Uri.parse("content://synthetic/A")))
        val b = org.fossify.messages.inkpilot.ui.StudioRoute.Compose(listOf("+15550102222"), "B caption", listOf(Uri.parse("content://synthetic/B")))
        assertNotEquals(a.composerId, b.composerId)
        try {
            androidx.test.core.app.ActivityScenario.launch(org.fossify.messages.inkpilot.ui.StudioHomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.openRoute(a); activity.openRoute(b) }
                scenario.recreate()
                scenario.onActivity { activity ->
                    fun children(view: android.view.View): List<android.view.View> = listOf(view) +
                        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
                    val editors = children(activity.window.decorView).filterIsInstance<android.widget.EditText>()
                    assertEquals(b.recipients.single(), editors.first { it.hint == "Recipients separated by |" }.text.toString())
                    assertEquals(b.body, editors.first { it.hint == "Message" }.text.toString())
                    val stored = ComposerStore(activity).load(b.composerId)!!
                    assertEquals(b.recipients.single(), stored.recipient)
                    assertEquals(b.body, stored.body)
                    assertEquals(b.mediaUris, stored.attachmentUris)
                    assertEquals(a.mediaUris, ComposerStore(activity).load(a.composerId)?.attachmentUris)
                }
            }
        } finally {
            ComposerStore(context).clear(a.composerId)
            ComposerStore(context).clear(b.composerId)
        }
    }

    @Test fun concurrentHostsClaimOneCarrierHandoffEvenWhenAccepted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = -(java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val request = ComposerStore(context).getOrCreate(OutgoingMessage(id, "+15550103333", "race", emptyList(), 3))
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<DispatchOutcome> {
                    val store = ComposerStore(context)
                    val sameDraft = store.getOrCreate(request)
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    store.submit(sameDraft, MessageDispatcher({ true }, { true }) { calls.incrementAndGet(); DispatchOutcome.Accepted() })
                }
            }.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertEquals(1, results.count { it is DispatchOutcome.Accepted })
            assertEquals(1, results.count { it is DispatchOutcome.Rejected })
            assertNull(ComposerStore(context).load(id))
            assertTrue(ComposerStore(context).submit(request, MessageDispatcher({ true }, { true }) { fail("Recreated host must not replay"); DispatchOutcome.Accepted() }) is DispatchOutcome.Rejected)
        } finally { pool.shutdownNow(); ComposerStore(context).clear(id) }
    }

    @Test fun preclaimExceptionAllowsRetryButDurableUnknownDoesNot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ComposerStore(context)
        val id = -81015L
        store.clear(id)
        try {
            val request = store.getOrCreate(OutgoingMessage(id, "+15550104444", "kept", emptyList(), 3))
            val failed = org.fossify.messages.inkpilot.ui.prepareComposerSend(store, request) { throw SecurityException("Synthetic SIM lookup revocation") }
            assertTrue(failed.isFailure)
            assertTrue(org.fossify.messages.inkpilot.ui.composerCanRetry(store, request))
            assertTrue(store.submit(request, MessageDispatcher({ true }, { true }) { DispatchOutcome.Unknown("fixture") }) is DispatchOutcome.Unknown)
            assertFalse(org.fossify.messages.inkpilot.ui.composerCanRetry(ComposerStore(context), request))
        } finally { store.clear(id) }
    }

}
