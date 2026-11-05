package org.fossify.messages.inkpilot.ui

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioRouteTest {
    @Test fun restoresEachTabsStackAndUnsentState() {
        val navigator = StudioNavigator()
        navigator.open(StudioRoute.Tab(StudioTab.CONTACTS))
        navigator.open(StudioRoute.Thread(42))
        val compose = StudioRoute.Compose(listOf("+15551234567"), "unsent", listOf(Uri.parse("content://media/9")))
        navigator.open(compose)
        navigator.open(StudioRoute.Tab(StudioTab.SMS))
        navigator.open(StudioRoute.Thread(7))
        val saved = Bundle().also(navigator::save)
        val restored = StudioNavigator(saved)
        assertEquals(StudioRoute.Thread(7), restored.current)
        restored.open(StudioRoute.Tab(StudioTab.CONTACTS))
        assertEquals(compose, restored.current)
        assertTrue(restored.pop())
        assertEquals(StudioRoute.Thread(42), restored.current)
        assertTrue(restored.pop())
        assertEquals(StudioRoute.Tab(StudioTab.CONTACTS), restored.current)
        assertFalse(restored.pop())
    }

    @Test fun completedComposeDoesNotReturnWithSeedTextOnBack() {
        val route = StudioRoute.Compose(listOf("+15550105555"), "sent caption", emptyList())
        val navigator = StudioNavigator()
        navigator.open(route)
        navigator.open(StudioRoute.Tab(StudioTab.CONTACTS))
        navigator.open(route)
        assertTrue(navigator.forget(route))
        assertEquals(StudioRoute.Tab(StudioTab.CONTACTS), navigator.current)
        assertFalse(navigator.pop())
        navigator.open(StudioRoute.Tab(StudioTab.SMS))
        assertEquals(StudioRoute.Tab(StudioTab.SMS), navigator.current)
        assertFalse(navigator.pop())
        val panels = StudioCanvasState()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = panels.view(route) { android.widget.EditText(context) } as android.widget.EditText
            editor.setText("sent caption")
            panels.discard(route)
            val fresh = panels.view(route) { android.widget.EditText(context) } as android.widget.EditText
            assertEquals("", fresh.text.toString())
            fresh.setText("another sent reply")
            val restored = StudioCanvasState(panels.save())
            restored.discard(route)
            val afterRecreation = restored.view(route) { android.widget.EditText(context) } as android.widget.EditText
            assertEquals("", afterRecreation.text.toString())
        }
    }

    @Test fun roundTripsEveryDestination() {
        listOf(StudioRoute.Draft("draft-1"), StudioRoute.Settings("sms"), StudioRoute.Soul,
            StudioRoute.Model, StudioRoute.Auxiliary("drafts"), StudioRoute.PhoneDial("5550100"),
            StudioRoute.PhoneHistory, StudioRoute.Attachment(Uri.parse("content://mms/part/9"), "image/png", "reference.png")).forEach { route ->
            val navigator = StudioNavigator()
            navigator.open(route)
            assertEquals(route, StudioNavigator(Bundle().also(navigator::save)).current)
        }
    }

    @Test fun dialIngressSelectsPhoneStackAndPreservesFilledNumber() {
        val navigator = StudioNavigator()
        navigator.acceptExternalRoute(StudioRoute.PhoneDial("5550100"))
        assertEquals(StudioTab.PHONE, navigator.selectedTab)
        assertEquals(StudioRoute.PhoneDial("5550100"), navigator.current)
        assertTrue(navigator.pop())
        assertEquals(StudioRoute.Tab(StudioTab.PHONE), navigator.current)
    }

    @Test fun dailyTabSelectionLeavesSideCardAndRetainsDailyCanvas() {
        val navigator = StudioNavigator()
        navigator.open(StudioRoute.Tab(StudioTab.PHONE))
        navigator.open(StudioRoute.PhoneDial("5550100"))
        navigator.open(StudioRoute.Soul)
        navigator.open(StudioRoute.Model)
        assertTrue(navigator.current.isSideRoute())
        navigator.selectDailyTab(StudioTab.PHONE)
        assertEquals(StudioRoute.PhoneDial("5550100"), navigator.current)
        navigator.open(StudioRoute.Soul)
        navigator.selectDailyTab(StudioTab.CONTACTS)
        assertEquals(StudioRoute.Tab(StudioTab.CONTACTS), navigator.current)
        navigator.selectDailyTab(StudioTab.PHONE)
        assertEquals(StudioRoute.PhoneDial("5550100"), navigator.current)
    }

    @Test fun panelRetainsLiveViewAndRestoresUnsentText() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val route = StudioRoute.Tab(StudioTab.SMS)
            val panels = StudioCanvasState()
            val editor = panels.view(route) { android.widget.EditText(context) } as android.widget.EditText
            editor.setText("unsent reply")
            editor.setSelection(4)
            assertSame(editor, panels.view(route) { error("must retain live panel") })
            val recreated = StudioCanvasState(panels.save())
            val restored = recreated.view(route) { android.widget.EditText(context) } as android.widget.EditText
            assertEquals("unsent reply", restored.text.toString())
            assertEquals(4, restored.selectionStart)
        }
    }

    @Test fun delayedRowsRestoreScrollAcrossRecreationAndResumeRefresh() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val route = StudioRoute.Tab(StudioTab.SMS)
        fun create(loading: Boolean) = StudioScrollView(context, loading).apply {
            addView(android.widget.TextView(context).apply {
                text = if (loading) "Loading messages…" else (1..100).joinToString("\n")
            })
        }
        fun layout(view: android.view.View) {
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 200)
        }
        lateinit var panels: StudioCanvasState
        instrumentation.runOnMainSync {
            val initial = StudioCanvasState()
            val original = initial.view(route) { create(false) }
            layout(original)
            original.scrollTo(0, 300)
            // Rotation starts a fresh loading panel; rotate again before rows arrive.
            val recreated = StudioCanvasState(initial.save())
            val loading = recreated.view(route) { create(true) }
            layout(loading)
            assertEquals(0, loading.scrollY)
            panels = StudioCanvasState(recreated.save())
        }
        repeat(2) { pass ->
            val loaded = java.util.concurrent.CountDownLatch(1)
            val position = java.util.concurrent.atomic.AtomicInteger(-1)
            instrumentation.runOnMainSync {
                if (pass == 1) {
                    // Same save/clear/re-render sequence as StudioHomeActivity.onResume.
                    panels.save()
                    panels.clear()
                }
                val scroll = panels.view(route) { create(true) } as StudioScrollView
                layout(scroll)
                assertEquals(0, scroll.scrollY)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    (scroll.getChildAt(0) as android.widget.TextView).text = (1..100).joinToString("\n")
                    scroll.contentReady()
                    layout(scroll)
                    position.set(scroll.scrollY)
                    loaded.countDown()
                }, 100)
            }
            assertTrue("delayed content callback", loaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("scroll after delayed load, pass $pass", 300, position.get())
        }
    }

    @Test fun stopCancelsQueuedAndLateProviderRefresh() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        var registered: android.database.ContentObserver? = null
        var refreshed = 0
        lateinit var observer: SmsRefreshObserver
        instrumentation.runOnMainSync {
            observer = SmsRefreshObserver({ true }, { _, value -> registered = value }, {}, { refreshed++ })
            observer.start()
            registered!!.onChange(false)
            observer.stop()
            registered!!.onChange(false)
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync { assertEquals(0, refreshed) }
    }

    @Test fun secondRegistrationFailureRollsBackAndCanRetry() {
        var attempts = 0
        var removals = 0
        val observer = SmsRefreshObserver({ true }, { _, _ ->
            attempts++
            if (attempts == 2) throw SecurityException("permission revoked")
        }, { removals++ }, {})
        observer.start()
        assertEquals(1, removals)
        observer.start()
        assertEquals(5, attempts)
        observer.start()
        assertEquals(5, attempts)
        observer.stop()
        observer.stop()
        assertEquals(2, removals)
    }
}
