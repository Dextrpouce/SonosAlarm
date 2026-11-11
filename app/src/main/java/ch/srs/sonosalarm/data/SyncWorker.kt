package ch.srs.sonosalarm.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Picks up alarms changed from the Sonos app, so the phone schedule and widget stay right. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = AlarmRepository(applicationContext)
        if (repo.store.speaker == null) return Result.success()
        // Away from home the speaker is unreachable: keep the cached schedule.
        if (runCatching { repo.refresh() }.isSuccess && repo.store.pauses.isNotEmpty()) {
            PauseManager.apply(applicationContext)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sonos-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
