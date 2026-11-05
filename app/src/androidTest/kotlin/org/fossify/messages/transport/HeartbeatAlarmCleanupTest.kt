package org.fossify.messages.transport

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.fossify.messages.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeartbeatAlarmCleanupTest {
    @Test fun missingCredentialCancelsBothOutboundPickupSchedules() {
        assumeFalse(BuildConfig.OPTIONAL_BRIDGE_ENABLED)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(context.storedDeviceCredential())
        val manager = WorkManager.getInstance(context)
        val oneShot = OneTimeWorkRequestBuilder<OutboundPickupWorker>()
            .setInitialDelay(1, TimeUnit.DAYS).build()
        val periodic = PeriodicWorkRequestBuilder<OutboundPickupWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        try {
            manager.enqueueUniqueWork("outbound_pickup", ExistingWorkPolicy.REPLACE, oneShot)
                .result.get(15, TimeUnit.SECONDS)
            manager.enqueueUniquePeriodicWork(
                "outbound_pickup_periodic", ExistingPeriodicWorkPolicy.REPLACE, periodic
            ).result.get(15, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(oneShot.id).get(15, TimeUnit.SECONDS)?.state)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(periodic.id).get(15, TimeUnit.SECONDS)?.state)

            HeartbeatAlarmReceiver().onReceive(context, Intent(context, HeartbeatAlarmReceiver::class.java))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                val first = manager.getWorkInfoById(oneShot.id).get(5, TimeUnit.SECONDS)?.state
                val second = manager.getWorkInfoById(periodic.id).get(5, TimeUnit.SECONDS)?.state
                if (first == WorkInfo.State.CANCELLED && second == WorkInfo.State.CANCELLED) return
                Thread.sleep(25)
            }
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(oneShot.id).get(5, TimeUnit.SECONDS)?.state)
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(periodic.id).get(5, TimeUnit.SECONDS)?.state)
        } finally {
            manager.cancelUniqueWork("outbound_pickup").result.get(15, TimeUnit.SECONDS)
            manager.cancelUniqueWork("outbound_pickup_periodic").result.get(15, TimeUnit.SECONDS)
        }
    }
}
