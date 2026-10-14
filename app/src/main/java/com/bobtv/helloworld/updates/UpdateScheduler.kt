package com.bobtv.helloworld.updates

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    const val INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val PERIODIC_NAME = "bobtv-periodic-update"
    const val STARTUP_NAME = "bobtv-startup-update"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context, due: Boolean) {
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(6, TimeUnit.HOURS).setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        if (due) {
            val startup = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork(STARTUP_NAME, ExistingWorkPolicy.KEEP, startup)
        }
    }
}

class UpdateWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        when (val result = UpdateServices.get(applicationContext).check(force = true, cancelled = { isStopped })) {
            is UpdateResult.Failed -> if (result.retryable && runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    } catch (_: CancellationException) { Result.retry() }
}
