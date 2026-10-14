package com.bobtv.helloworld.updates

import android.content.Context
import org.robolectric.RuntimeEnvironment
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateSchedulerTest {
    @Test fun repeatedStartupKeepsOnlyOnePeriodicAndOneImmediateRequest() {
        val context: Context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        try {
            repeat(3) { UpdateScheduler.schedule(context, due = true) }
            val periodic = manager.getWorkInfosForUniqueWork(UpdateScheduler.PERIODIC_NAME).get(5, TimeUnit.SECONDS)
            val startup = manager.getWorkInfosForUniqueWork(UpdateScheduler.STARTUP_NAME).get(5, TimeUnit.SECONDS)
            assertEquals(1, periodic.size)
            assertEquals(1, startup.size)
            assertEquals(UpdateScheduler.INTERVAL_MS, 6 * 60 * 60 * 1000L)
        } finally { manager.cancelAllWork().result.get(5, TimeUnit.SECONDS) }
    }
}
