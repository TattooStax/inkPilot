package org.fossify.messages.inkpilot.smoke

import android.content.ContentValues
import android.content.ContentUris
import android.os.Build
import android.provider.Telephony
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.inkpilot.ui.*
import org.fossify.messages.messaging.key
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic provider data only. CI must provision SMS role and permissions on a disposable AVD. */
@RunWith(AndroidJUnit4::class)
class InShellThreadTest {
    @Test fun repeatedRotationWaitsForAllPreviouslyLoadedPages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val route = StudioRoute.Thread(42)
            val initial = StudioCanvasState()
            val list = initial.view(route) { StudioPagedList(instrumentation.targetContext) } as StudioPagedList
            list.contentReady(80, true)
            var requests = 0
            val restoredPanels = StudioCanvasState(initial.save())
            val restored = restoredPanels.view(route) {
                StudioPagedList(instrumentation.targetContext).apply { loadMore = { requests++ } }
            } as StudioPagedList
            assertEquals(1, requests)
            restored.contentReady(40, true)
            assertEquals(2, requests)
            val rotatedAgain = StudioCanvasState(restoredPanels.save())
            val pending = rotatedAgain.view(route) {
                StudioPagedList(instrumentation.targetContext).apply { loadMore = { requests++ } }
            } as StudioPagedList
            assertEquals(3, requests)
            pending.contentReady(80, true)
            assertEquals(3, requests)
        }
    }

    @Test fun reaches151stThreadAndKeepsMixedThreadInsideShellAcrossRotation() {
        assumeTrue("Disposable emulator only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("CI must assign the SMS role", Telephony.Sms.getDefaultSmsPackage(context) == context.packageName)
        val resolver = context.contentResolver
        val created = mutableListOf<android.net.Uri>()
        val threads = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        val legacy = instrumentation.addMonitor(ThreadActivity::class.java.name, null, true)
        try {
            repeat(151) { index ->
                val number = "+1555900${index.toString().padStart(4, '0')}"
                val thread = Telephony.Threads.getOrCreateThreadId(context, setOf(number))
                threads += thread
                created += requireNotNull(resolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
                    put("thread_id", thread); put("address", number); put("body", "task9 fixture $index")
                    put("type", 1); put("read", 0); put("date", now - index)
                }))
            }
            val thread = threads.first()
            val mms = requireNotNull(resolver.insert(Telephony.Mms.CONTENT_URI, ContentValues().apply {
                put("thread_id", thread); put("date", now / 1000); put("msg_box", 1)
                put("m_type", 132); put("read", 0); put("sub_id", -1)
            }))
            created += mms
            resolver.insert(android.net.Uri.parse("content://mms/${ContentUris.parseId(mms)}/part"), ContentValues().apply {
                put("ct", "text/plain"); put("text", "task9 MMS fixture")
            })
            val gateway = ConversationGateway(context)
            val found = mutableSetOf<Long>()
            var cursor: ThreadCursor? = null
            do {
                val page = gateway.pageThreads(cursor, 40)
                assertTrue("keyset pages do not repeat", page.items.none { it.threadId in found })
                found += page.items.map { it.threadId }
                cursor = page.next
            } while (cursor != null)
            assertTrue("151st conversation reachable", found.containsAll(threads))
            val mixed = gateway.pageMessages(thread, null, 40).items
            assertTrue(mixed.any { it.isMMS && it.body == "task9 MMS fixture" })
            assertTrue(mixed.any { !it.isMMS && it.body == "task9 fixture 0" })
            assertEquals(mixed.size, mixed.map { it.key() }.toSet().size)
            ActivityScenario.launch(StudioHomeActivity::class.java).use { scenario ->
                // Exercise RecyclerView paging and the actual row callback past the former 150 cap.
                SmokeViews.await<Boolean>("initial inbox page") {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(org.fossify.messages.R.id.inkpilot_message_list).adapter!!.itemCount >= 40
                    }
                    if (ready) true else null
                }
                listOf(80, 120, 151).forEach { needed ->
                    scenario.onActivity { activity ->
                        val inbox = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(org.fossify.messages.R.id.inkpilot_message_list)
                        inbox.scrollToPosition(inbox.adapter!!.itemCount - 1)
                    }
                    SmokeViews.await<Boolean>("inbox page $needed") {
                        var ready = false
                        scenario.onActivity { activity ->
                            ready = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(org.fossify.messages.R.id.inkpilot_message_list).adapter!!.itemCount >= needed
                        }
                        if (ready) true else null
                    }
                }
                var lastPosition = -1
                scenario.onActivity { activity ->
                    val inbox = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(org.fossify.messages.R.id.inkpilot_message_list)
                    lastPosition = (0 until inbox.adapter!!.itemCount).first { inbox.adapter!!.getItemId(it) == threads.last() }
                    inbox.scrollToPosition(lastPosition)
                }
                SmokeViews.await<Boolean>("151st row callback") {
                    var clicked = false
                    scenario.onActivity { activity ->
                        val inbox = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(org.fossify.messages.R.id.inkpilot_message_list)
                        clicked = inbox.findViewHolderForAdapterPosition(lastPosition)?.itemView?.performClick() == true
                    }
                    if (clicked) true else null
                }
                SmokeViews.assertText("task9 fixture 150")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed(); it.openRoute(StudioRoute.Thread(thread)) }
                SmokeViews.assertText("Client reference images")
                SmokeViews.assertText("task9 MMS fixture")
                SmokeViews.assertText("task9 fixture 0")
                scenario.recreate()
                SmokeViews.assertText("Client reference images")
                SmokeViews.assertText("task9 MMS fixture")
                resolver.update(created.first(), ContentValues().apply { put("body", "task9 refreshed") }, null, null)
                SmokeViews.assertText("task9 refreshed")
                SmokeViews.clickDescription("Phone")
                resolver.update(created.first(), ContentValues().apply { put("body", "task9 hidden refresh") }, null, null)
                // Ensure the debounced observer runs while Phone, rather than Thread, is current.
                android.os.SystemClock.sleep(1000)
                SmokeViews.clickDescription("SMS")
                SmokeViews.assertText("task9 hidden refresh")
                SmokeViews.assertText("Client reference images")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                SmokeViews.assertText("New message")
            }
            assertEquals("Never starts inherited thread activity", 0, legacy.hits)
            resolver.query(created.first(), arrayOf("read"), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally {
            instrumentation.removeMonitor(legacy)
            created.asReversed().forEach { resolver.delete(it, null, null) }
        }
    }
}
