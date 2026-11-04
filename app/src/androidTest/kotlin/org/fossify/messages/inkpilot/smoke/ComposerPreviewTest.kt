package org.fossify.messages.inkpilot.smoke

import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.activities.render
import org.fossify.messages.adapters.AttachmentsAdapter
import org.fossify.messages.messaging.*
import org.fossify.messages.models.AttachmentSelection
import org.fossify.messages.models.SIMCard
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import android.os.Process
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val previewUri = Uri.parse("content://org.fossify.messages.task5.revocable-preview/image.png")
private const val restartThread = 7654322L
private fun storedPreview() = OutgoingMessage(restartThread, "+12025550123", "human text with required media", listOf(previewUri), 72, "task5-preview")

/** Exercises Glide's actual asynchronous onLoadFailed path in a rendered RecyclerView. */
@RunWith(AndroidJUnit4::class)
class ComposerPreviewTest {
    @Test fun revokedReadRetainsVisibleMediaAfterActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ComposerStore(context)
        try {
            context.contentResolver.call(previewUri, "grant", null, null)
            assertTrue(store.save(storedPreview()))
            context.contentResolver.openInputStream(previewUri)!!.use { assertTrue(it.read() >= 0) }
            context.contentResolver.call(previewUri, "revoke", null, null)
            assertEquals("image/png", context.contentResolver.getType(previewUri))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertUnavailableRendered(scenario, requireNotNull(store.load(restartThread)))
                scenario.recreate()
                assertUnavailableRendered(scenario, requireNotNull(ComposerStore(context).load(restartThread)))
            }
        } finally {
            store.clear(restartThread)
            context.contentResolver.call(previewUri, "cleanup", null, null)
        }
    }

    @Test fun readableButUndecodableMediaAlsoBlocksProductionComposerDispatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            context.contentResolver.call(previewUri, "corrupt", null, null)
            context.contentResolver.openInputStream(previewUri)!!.use { assertTrue(it.read() >= 0) }
            ActivityScenario.launch(MainActivity::class.java).use { assertUnavailableRendered(it, storedPreview()) }
        } finally {
            ComposerStore(context).clear(restartThread)
            context.contentResolver.call(previewUri, "cleanup", null, null)
        }
    }

    @Test fun restoredSimLabelMatchesDispatchForEitherInitializationOrder() {
        val cards = listOf(SIMCard(1, 31, "SIM A"), SIMCard(2, 72, "SIM B"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (restoreFirst in listOf(true, false)) {
                    val selection = ComposerSimSelection()
                    val label = TextView(activity)
                    if (restoreFirst) selection.restore(72)
                    selection.update(cards, 31)
                    if (!restoreFirst) selection.restore(72)
                    selection.render(label)
                    assertEquals("2", label.text.toString())
                    assertEquals("SIM B", label.contentDescription)
                    var sentSubscription: Int? = null
                    MessageDispatcher({ true }, { true }) { sentSubscription = it.subscriptionId; DispatchOutcome.Accepted() }
                        .submit(storedPreview().copy(attachmentUris = emptyList(), subscriptionId = selection.subscriptionId))
                    assertEquals(72, sentSubscription)
                    selection.update(listOf(cards[1].copy(id = 1)), 72)
                    selection.render(label)
                    assertEquals("1", label.text.toString())
                    assertEquals(72, selection.subscriptionId)
                }
            }
        }
    }
}

private fun assertUnavailableRendered(scenario: ActivityScenario<MainActivity>, request: OutgoingMessage) {
    val failed = CountDownLatch(1)
    lateinit var adapter: AttachmentsAdapter
    scenario.onActivity { activity ->
        val recycler = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        activity.setContentView(FrameLayout(activity).apply {
            addView(recycler, FrameLayout.LayoutParams(-1, -1))
        })
        adapter = AttachmentsAdapter(activity, recycler, {}, {
            if (adapter.attachments.any { it.isUnavailable }) failed.countDown()
        })
        recycler.adapter = adapter
        request.attachmentUris.forEach { uri ->
            adapter.addAttachment(AttachmentSelection(uri.toString(), uri, "image/png", "image.png", false))
        }
    }
    assertTrue("Actual Glide failure must render", failed.await(15, TimeUnit.SECONDS))
    scenario.onActivity { activity ->
        assertEquals(request.attachmentUris, adapter.attachments.map { it.uri })
        assertTrue(adapter.attachments.single().isUnavailable)
        val unavailable = activity.findViewById<TextView>(R.id.unavailable_message)
        assertEquals(View.VISIBLE, unavailable.visibility)
        assertEquals(activity.getString(R.string.attachment_unavailable), unavailable.text.toString())
        var sends = 0
        val dispatcher = MessageDispatcher(
            { uri -> activity.contentResolver.openInputStream(uri)?.use { it.read(); true } ?: false }, { true }
        ) { sends++; DispatchOutcome.Accepted() }
        val store = ComposerStore(activity)
        val outcome = store.submit(request.copy(attachmentUris = adapter.attachments.map { it.uri }), dispatcher,
            adapter.attachments.filter { it.isUnavailable }.map { it.uri }.toSet())
        assertTrue(outcome is DispatchOutcome.Rejected)
        assertEquals(0, sends)
        assertEquals(request, ComposerStore(activity).load(request.threadId))
    }
}

/** Run this class, force-stop only the disposable emulator target package, then run Verify. */
@RunWith(AndroidJUnit4::class)
class ComposerRestartPrepareTest {
    @Test fun persistReadableMediaForNextProcess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("task5RestartPhase") == "prepare")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.call(previewUri, "grant", null, null)
        assertTrue(ComposerStore(context).save(storedPreview()))
        check(context.getSharedPreferences("task5_restart_proof", 0).edit().putInt("pid", Process.myPid()).commit())
        context.contentResolver.openInputStream(previewUri)!!.use { assertTrue(it.read() >= 0) }
    }
}

@RunWith(AndroidJUnit4::class)
class ComposerRestartVerifyTest {
    @Test fun newProcessRetainsRevokedMediaWithoutTextFallback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("task5RestartPhase") == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ComposerStore(context)
        assertNotEquals("Target process must have restarted", Process.myPid(), context.getSharedPreferences("task5_restart_proof", 0).getInt("pid", -1))
        try {
            val request = requireNotNull(store.load(restartThread))
            assertEquals(storedPreview(), request)
            context.contentResolver.call(previewUri, "revoke", null, null)
            ActivityScenario.launch(MainActivity::class.java).use { assertUnavailableRendered(it, request) }
        } finally {
            store.clear(restartThread)
            context.contentResolver.call(previewUri, "cleanup", null, null)
        }
    }
}
