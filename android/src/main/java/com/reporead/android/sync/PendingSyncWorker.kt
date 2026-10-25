// Owns: the queued upload of pending Room changes; not device-log upload or Save all.

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
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.ApiException
import java.time.Duration

// WorkManager persists this name and the worker's class name. Keep the name stable so a send queued before an app
// update is replaced, not doubled. A renamed class cannot start a send queued under its old name; its changes then go
// with the next change or Library open.
private const val WORK_NAME = "sync-local-changes"

/** A burst of reading saves (one per scroll stop) becomes one sync. */
private val SETTLE: Duration = Duration.ofSeconds(5)

/**
 * Asks for one background sync once the phone has a network. A change saved while a run is under way replaces that run,
 * so the run cannot miss it; sending again is idempotent (mutation ids, last-write-wins positions; ADR-08).
 */
internal fun requestPendingChangeSend(context: Context) {
    val request =
        OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(SETTLE)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF)
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
}

class PendingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        sendPendingChangesInBackground((applicationContext as RepoReadApplication).phoneSync, runAttemptCount)
}

/**
 * One background send: sends what waits, as library open does, but loads no lists (PhoneSync.sendPendingChangesIfAny).
 * An unreachable server, a 5xx, or GitHub unavailable is tried again by WorkManager; anything else (signed out, access
 * denied) waits for the next change or library open, which show it. [attempt] counts from 0.
 */
internal suspend fun sendPendingChangesInBackground(phoneSync: PhoneSync, attempt: Int): Result {
    val sendResult =
        try {
            phoneSync.sendPendingChangesIfAny()
        } catch (failure: ApiException) {
            return if (shouldRetry("Pending-change sender", failure, attempt)) Result.retry() else Result.failure()
        }
    if (sendResult == null) {
        DeviceLog.info("Pending-change send: nothing waiting; attempt=$attempt")
        return Result.success()
    }
    sendResult.notSent?.let {
        return if (shouldRetry("Pending-change sender", it, attempt)) Result.retry() else Result.failure()
    }
    DeviceLog.info("Pending-change send done; attempt=$attempt refused=${sendResult.refused.size}")
    return Result.success()
}
