// Owns: the queued upload of pending Room changes; not device-log upload or saved-page prefetch.

package com.reporead.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.reporead.android.RepoReadApplication
import com.reporead.android.logging.AppLog
import com.reporead.android.network.ApiException
import java.time.Duration

private const val WORK_NAME = "sync-local-changes"

/** A burst of reading saves (one per scroll stop) becomes one sync. */
private val SETTLE: Duration = Duration.ofSeconds(5)

/**
 * Asks for one background sync once the phone has a network. A change saved while a run is under way replaces that run,
 * so the run cannot miss it; sending again is idempotent (mutation ids, last-write-wins positions; ADR-08).
 */
internal fun requestSync(context: Context) {
    val request =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(SETTLE)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF)
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        backgroundSync((applicationContext as RepoReadApplication).sync, runAttemptCount)
}

/**
 * One background run: sends what waits, as library open does, but loads no lists (Sync.syncWaitingChanges). An
 * unreachable server, a 5xx, or GitHub unavailable is tried again by WorkManager; anything else (signed out, access
 * denied) waits for the next change or library open, which show it. [attempt] counts from 0.
 */
internal suspend fun backgroundSync(sync: Sync, attempt: Int): Result {
    val syncResult =
        try {
            sync.syncWaitingChanges()
        } catch (failure: ApiException) {
            return if (shouldRetry("Background sync", failure, attempt)) Result.retry() else Result.failure()
        }
    if (syncResult == null) {
        AppLog.info("Background sync: nothing waiting; attempt=$attempt")
        return Result.success()
    }
    syncResult.notSent?.let {
        return if (shouldRetry("Background sync", it, attempt)) Result.retry() else Result.failure()
    }
    AppLog.info("Background sync done; attempt=$attempt refused=${syncResult.refused.size}")
    return Result.success()
}
