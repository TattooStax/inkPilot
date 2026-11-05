package org.fossify.messages.inkpilot.smoke

import android.content.ComponentName
import android.content.ClipData
import android.app.PendingIntent
import org.fossify.messages.messaging.ComposerStore
import java.util.UUID
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.messages.activities.*
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.inkpilot.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellIngressTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** ActivityMonitor sees onCreate; route rendering happens when the shell resumes. */
    private fun awaitRenderedTitle(activity: StudioHomeActivity, expected: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var actual = ""
        do {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                actual = activity.findViewById<android.widget.TextView>(
                    org.fossify.messages.R.id.inkpilot_title)?.text?.toString().orEmpty()
            }
            if (actual == expected) return actual
            Thread.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return actual
    }

    @Test fun shareAndSmsIntentsKeepSeparateRecipientsBodyAndGrantedMedia() {
        val a = Uri.parse("content://synthetic/reference-a")
        val b = Uri.parse("content://synthetic/reference-b")
        val incoming = Intent(context, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "Client private reference")
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val route = ExternalRouteParser.parse(incoming) as StudioRoute.Compose
        assertEquals("Client private reference", route.body)
        assertEquals(listOf(a, b), route.mediaUris)
        assertTrue(route.recipients.isEmpty())
        val forwarded = ExternalRouteParser.forward(context, incoming)
        assertEquals(listOf(a, b), (0 until forwarded.clipData!!.itemCount).map { forwarded.clipData!!.getItemAt(it).uri })
        assertTrue(forwarded.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val routed = forwarded.getBundleExtra(ExternalRouteParser.ROUTE)!!.decodeRoute() as StudioRoute.Compose
        assertEquals(route.recipients, routed.recipients)
        assertEquals(route.body, routed.body)
        assertEquals(route.mediaUris, routed.mediaUris)
        val sms = Intent(context, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("smsto:+15550101111,+15550102222?body=Tattoo%20reference")
        }
        val compose = ExternalRouteParser.parse(sms) as StudioRoute.Compose
        assertEquals(listOf("+15550101111", "+15550102222"), compose.recipients)
        assertEquals("Tattoo reference", compose.body)
        assertNotEquals(route.composerId, compose.composerId)
        val reordered = Intent(context, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("smsto:+15550101111?subject=Outline&body=Tattoo%20reference%20%26%20notes")
        }
        assertEquals("Tattoo reference & notes", (ExternalRouteParser.parse(reordered) as StudioRoute.Compose).body)
        val encodedPercent = Intent(context, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("smsto:+15550101111?body=100%2520proof&subject=Outline")
        }
        assertEquals("100%20proof", (ExternalRouteParser.parse(encodedPercent) as StudioRoute.Compose).body)
        val oldDraft = Intent(context, ThreadActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(THREAD_ID, 91L)
            putExtra(org.fossify.messages.helpers.THREAD_NUMBER, "[\"+15550101111\",\"+15550102222\"]")
            putExtra(org.fossify.messages.helpers.THREAD_TEXT, "Outline approved")
            putExtra(org.fossify.messages.helpers.THREAD_ATTACHMENT_URI, a.toString())
        }
        val legacy = ExternalRouteParser.parse(oldDraft) as StudioRoute.Compose
        assertEquals(listOf("+15550101111", "+15550102222"), legacy.recipients)
        assertEquals("Outline approved", legacy.body)
        assertEquals(listOf(a), legacy.mediaUris)
        assertNotEquals(compose.composerId, legacy.composerId)
    }

    @Test fun oldExplicitComponentsResolveToSecureIngressTargets() {
        val packageManager = context.packageManager
        val aliases = listOf(
            MainActivity::class.java to CompatibilityIngressActivity::class.java,
            ThreadActivity::class.java to CompatibilityIngressActivity::class.java,
            NewConversationActivity::class.java to CompatibilityIngressActivity::class.java,
            SettingsActivity::class.java to SettingsIngressActivity::class.java,
            ArchivedConversationsActivity::class.java to ArchiveIngressActivity::class.java,
            RecycleBinConversationsActivity::class.java to RecycleIngressActivity::class.java,
            ConversationDetailsActivity::class.java to DetailsIngressActivity::class.java,
            VCardViewerActivity::class.java to VCardIngressActivity::class.java,
        )
        aliases.forEach { (old, target) ->
            @Suppress("DEPRECATION")
            val info = packageManager.getActivityInfo(ComponentName(context, old), 0)
            assertEquals(target.name, info.targetActivity)
            assertTrue(CompatibilityIngressActivity::class.java.isAssignableFrom(target))
        }
        assertEquals(StudioRoute.Thread(47), ExternalRouteParser.parse(Intent(context, ThreadActivity::class.java).putExtra(THREAD_ID, 47L)))
        assertEquals(StudioRoute.Auxiliary("archive"), ExternalRouteParser.parse(Intent(context, ArchivedConversationsActivity::class.java)))
        assertEquals(StudioRoute.Settings("messaging"), ExternalRouteParser.parse(Intent(Intent.ACTION_APPLICATION_PREFERENCES)))
        assertEquals(StudioRoute.Tab(StudioTab.SMS), ExternalRouteParser.parse(Intent(context, MainActivity::class.java)))
    }

    @Test fun historicalPendingIntentAndPreferencesResolveIntoShell() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shell = instrumentation.addMonitor(StudioHomeActivity::class.java.name, null, false)
        try {
            val old = PendingIntent.getActivity(context, 7311, Intent(context, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, 47L); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            old.send()
            val first = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(first)
            assertEquals("Conversation", awaitRenderedTitle(first!!, "Conversation"))
            instrumentation.runOnMainSync {
                assertNotNull(first.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_home))
                first.finish()
            }
            context.startActivity(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val explicitSettings = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(explicitSettings)
            assertEquals("Settings", awaitRenderedTitle(explicitSettings!!, "Settings"))
            instrumentation.runOnMainSync {
                explicitSettings.finish()
            }
            context.startActivity(Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val settings = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(settings)
            assertEquals("Settings", awaitRenderedTitle(settings!!, "Settings"))
            instrumentation.runOnMainSync {
                settings.onBackPressedDispatcher.onBackPressed()
                assertEquals("SMS", settings.findViewById<android.widget.TextView>(org.fossify.messages.R.id.inkpilot_title).text.toString())
                settings.finish()
            }
            context.startActivity(Intent(context, ArchivedConversationsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val archive = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(archive)
            assertEquals("Archive", awaitRenderedTitle(archive!!, "Archive"))
            instrumentation.runOnMainSync {
                archive.onBackPressedDispatcher.onBackPressed()
                assertEquals("SMS", archive.findViewById<android.widget.TextView>(org.fossify.messages.R.id.inkpilot_title).text.toString())
                archive.finish()
            }
            context.startActivity(Intent(context, RecycleBinConversationsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val recycle = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(recycle)
            assertEquals("Recycle", awaitRenderedTitle(recycle!!, "Recycle"))
            instrumentation.runOnMainSync {
                recycle.onBackPressedDispatcher.onBackPressed()
                assertEquals("SMS", recycle.findViewById<android.widget.TextView>(org.fossify.messages.R.id.inkpilot_title).text.toString())
                recycle.finish()
            }
            context.startActivity(Intent(context, ConversationDetailsActivity::class.java).apply {
                putExtra(THREAD_ID, 47L); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            val details = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(details)
            assertEquals("Details", awaitRenderedTitle(details!!, "Details"))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertEquals("SMS", awaitRenderedTitle(details, "SMS"))
            instrumentation.runOnMainSync { details.finish() }
            context.startActivity(Intent(context, VCardViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val vcard = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(vcard)
            assertEquals("Contacts", awaitRenderedTitle(vcard!!, "Contacts"))
            instrumentation.runOnMainSync {
                vcard.finish()
            }
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val up = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(up)
            assertEquals("SMS", awaitRenderedTitle(up!!, "SMS"))
            instrumentation.runOnMainSync {
                up.finish()
            }
            instrumentation.runOnMainSync {
                assertTrue(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).none { it is ThreadActivity })
            }
        } finally { instrumentation.removeMonitor(shell) }
    }

    @Test fun coldShareForwardsReadableUriAndPrivateBodyIntoOneComposer() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sender = instrumentation.context
        assertNotEquals(context.packageName, sender.packageName)
        val uri = Uri.parse("content://$INGRESS_AUTHORITY/reference?token=${UUID.randomUUID()}")
        @Suppress("DEPRECATION")
        val provider = sender.packageManager.getProviderInfo(ComponentName(sender.packageName, CrossPackageShareProvider::class.java.name), 0)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)

        val shell = instrumentation.addMonitor(StudioHomeActivity::class.java.name, null, false)
        var home: StudioHomeActivity? = null
        var composerId = 0L
        try {
            sender.startActivity(Intent().setClassName(sender.packageName, CrossPackageShareSender::class.java.name).apply {
                putExtra("shareUri", uri.toString())
                putExtra("targetPackage", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            home = shell.waitForActivityWithTimeout(10_000) as StudioHomeActivity?
            assertNotNull(home)
            val forwarded = home!!.intent
            val route = forwarded.getBundleExtra(ExternalRouteParser.ROUTE)!!.decodeRoute() as StudioRoute.Compose
            composerId = route.composerId
            assertEquals(listOf(uri), route.mediaUris)
            assertEquals(uri, forwarded.clipData!!.getItemAt(0).uri)
            assertTrue(forwarded.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            instrumentation.runOnMainSync {
                assertNotNull(home!!.contentResolver.openInputStream(uri)?.use { it.read() })
                assertEquals("Private client outline", ComposerStore(home!!).load(composerId)?.body)
                assertNotNull(home!!.findViewById<android.view.View>(org.fossify.messages.R.id.inkpilot_home))
            }
        } finally {
            instrumentation.runOnMainSync { home?.finish() }
            if (composerId != 0L) ComposerStore(context).clear(composerId)
            sender.startActivity(Intent().setClassName(sender.packageName, CrossPackageShareSender::class.java.name)
                .setAction(CrossPackageShareSender.CLEANUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.removeMonitor(shell)
        }
    }

    @Test fun lockedOldNotificationNeverInflatesLegacyOrPrivateShellContent() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = context.baseConfig
        assumeTrue(!config.isAppPasswordProtectionOn && config.appPasswordHash.isEmpty())
        val previousType = config.appProtectionType
        val previousTimestamp = config.lastUnlockTimestampMs
        val shell = instrumentation.addMonitor(StudioHomeActivity::class.java.name, null, false)
        val unlock = instrumentation.addMonitor(org.fossify.commons.activities.AppLockActivity::class.java.name, null, false)
        var home: android.app.Activity? = null
        var gate: android.app.Activity? = null
        try {
            instrumentation.runOnMainSync {
                config.appPasswordHash = java.security.MessageDigest.getInstance("SHA-1").digest("3947".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                config.appProtectionType = org.fossify.commons.helpers.PROTECTION_PIN
                config.isAppPasswordProtectionOn = true
                context.appLockManager.lock()
            }
            context.startActivity(Intent(context, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, 47L); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            home = shell.waitForActivityWithTimeout(10_000)
            gate = unlock.waitForActivityWithTimeout(10_000)
            assertNotNull(home); assertNotNull(gate)
            instrumentation.runOnMainSync {
                assertTrue(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).none { it is ThreadActivity })
                assertEquals(0, home!!.findViewById<android.view.ViewGroup>(org.fossify.messages.R.id.inkpilot_canvas).childCount)
                assertTrue(home!!.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(context.appLockManager.isLocked())
            }
            val privateUri = Uri.parse("content://synthetic/locked-outline")
            context.startActivity(Intent(context, NewConversationActivity::class.java).apply {
                action = Intent.ACTION_SEND; type = "image/png"; putExtra(Intent.EXTRA_TEXT, "Private locked reference")
                putExtra(Intent.EXTRA_STREAM, privateUri); clipData = ClipData.newRawUri("Locked reference", privateUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val routed = home!!.intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute() as? StudioRoute.Compose
                assertNotNull(routed)
                assertEquals("Private locked reference", routed!!.body)
                assertEquals(listOf(privateUri), routed.mediaUris)
                assertEquals(privateUri, home!!.intent.clipData?.getItemAt(0)?.uri)
                assertEquals(0, home!!.findViewById<android.view.ViewGroup>(org.fossify.messages.R.id.inkpilot_canvas).childCount)
                assertTrue(context.appLockManager.isLocked())
            }
        } finally {
            instrumentation.runOnMainSync {
                config.isAppPasswordProtectionOn = false; config.appPasswordHash = ""; config.appProtectionType = previousType
                context.appLockManager.unlock(); config.lastUnlockTimestampMs = previousTimestamp
                val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.values().flatMap { lifecycle.getActivitiesInStage(it) }
                    .filterIsInstance<org.fossify.commons.activities.AppLockActivity>()
                    .distinct().forEach { it.finish() }
                gate?.finish(); home?.finish()
            }
            instrumentation.removeMonitor(shell); instrumentation.removeMonitor(unlock)
        }
    }
}
