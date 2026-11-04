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
        navigator.open(StudioRoute.Compose(listOf("+15551234567"), "unsent", listOf(Uri.parse("content://media/9"))))
        navigator.open(StudioRoute.Tab(StudioTab.SMS))
        navigator.open(StudioRoute.Thread(7))
        val saved = Bundle().also(navigator::save)
        val restored = StudioNavigator(saved)
        assertEquals(StudioRoute.Thread(7), restored.current)
        restored.open(StudioRoute.Tab(StudioTab.CONTACTS))
        assertEquals(StudioRoute.Compose(listOf("+15551234567"), "unsent", listOf(Uri.parse("content://media/9"))), restored.current)
        assertTrue(restored.pop())
        assertEquals(StudioRoute.Thread(42), restored.current)
        assertTrue(restored.pop())
        assertEquals(StudioRoute.Tab(StudioTab.CONTACTS), restored.current)
        assertFalse(restored.pop())
    }

    @Test fun roundTripsEveryDestination() {
        listOf(StudioRoute.Draft("draft-1"), StudioRoute.Settings("sms"), StudioRoute.Soul,
            StudioRoute.Model, StudioRoute.Auxiliary("drafts")).forEach { route ->
            val navigator = StudioNavigator()
            navigator.open(route)
            assertEquals(route, StudioNavigator(Bundle().also(navigator::save)).current)
        }
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

    @Test fun restoresScrollAfterPanelRecreation() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            fun create() = android.widget.ScrollView(context).apply {
                addView(android.widget.TextView(context).apply { text = (1..100).joinToString("\n") })
            }
            fun layout(view: android.view.View) {
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 400, 200)
            }
            val route = StudioRoute.Tab(StudioTab.CONTACTS)
            val panels = StudioCanvasState()
            val original = panels.view(route, ::create)
            layout(original)
            original.scrollTo(0, 300)
            val restored = StudioCanvasState(panels.save()).view(route, ::create)
            layout(restored)
            assertEquals(300, restored.scrollY)
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
        assertEquals(4, attempts)
        observer.start()
        assertEquals(4, attempts)
        observer.stop()
        observer.stop()
        assertEquals(2, removals)
    }
}
