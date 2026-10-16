package com.reporead.android.sync

import android.content.Context
import android.util.Log
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
import com.reporead.android.core.network.ApiException
import java.time.Duration

private const val WORK_NAME = "sync-local-changes"

/** A burst of reading saves (one per scroll stop) becomes one sync. */
private val SETTLE: Duration = Duration.ofSeconds(5)

/**
 * Ten attempts have nine waits, doubling from 30 s: 30 s × (2⁹ − 1), about 4 hours 15 minutes of server or GitHub
 * failure. Time without a network does not count.
 */
private val FIRST_BACKOFF: Duration = Duration.ofSeconds(30)
private const val MAX_ATTEMPTS = 10

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
 * One background run: sends what waits and reads the server's lists, as library open does. An unreachable server, a
 * 5xx, or GitHub unavailable is tried again by WorkManager; anything else (signed out, access denied) waits for the
 * next change or library open, which show it. [attempt] counts from 0.
 */
internal suspend fun backgroundSync(sync: Sync, attempt: Int): Result {
    val synced =
        try {
            sync.syncWaitingChanges()
        } catch (error: ApiException) {
            return afterFailure(error, attempt)
        }
    if (synced == null) {
        Log.i("RepoRead", "Background sync: nothing waiting; attempt=$attempt")
        return Result.success()
    }
    synced.notSent?.let {
        return afterFailure(it, attempt)
    }
    Log.i("RepoRead", "Background sync done; attempt=$attempt refused=${synced.refused.size}")
    return Result.success()
}

private fun afterFailure(error: ApiException, attempt: Int): Result {
    val passing = error.unreachable || error.status >= 500
    val retry = passing && attempt + 1 < MAX_ATTEMPTS
    Log.w("RepoRead", "Background sync failed; attempt=$attempt status=${error.status} code=${error.code} retry=$retry")
    return if (retry) Result.retry() else Result.failure()
}
