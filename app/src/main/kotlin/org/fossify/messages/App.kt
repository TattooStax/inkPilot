package org.fossify.messages

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.helpers.MessagingCache

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        reconcileContactsObserver()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { reconcileContactsObserver() }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        getSystemService(android.app.AppOpsManager::class.java).startWatchingMode(
            android.app.AppOpsManager.OPSTR_READ_CONTACTS, packageName,
            android.app.AppOpsManager.OnOpChangedListener { _, _ ->
                Handler(Looper.getMainLooper()).post { reconcileContactsObserver() }
            }
        )

        ensureBackgroundThread {
            rescheduleAllScheduledMessages()
            runCatching { org.fossify.messages.inkpilot.runtime.AgentRuntime.reconcileHistory(this) }
            runCatching { org.fossify.messages.inkpilot.runtime.AgentRuntime.runPending(this) }
                .onFailure {
                    org.fossify.messages.inkpilot.runtime.AgentSettings(this)
                        .recordStatus("Local message recovery needs another try. Open Agent and retry.")
                }
        }
    }

    private val contactsPermissionLifecycle = ContactsPermissionLifecycle()

    internal fun reconcileContactsObserver() {
        contactsPermissionLifecycle.reconcile(hasPermission(PERMISSION_READ_CONTACTS), start = {
            listOf(ContactsContract.Contacts.CONTENT_URI, ContactsContract.Data.CONTENT_URI,
                ContactsContract.DisplayPhoto.CONTENT_URI).forEach {
                contentResolver.registerContentObserver(it, true, contactsObserver)
            }
        }, stop = {
            contentResolver.unregisterContentObserver(contactsObserver)
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        })
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}
