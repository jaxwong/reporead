// Owns: the queued upload of durable Android log batches and process-exit checkpoints.

package com.reporead.android.sync

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
import com.reporead.android.auth.StoredSession
import com.reporead.android.logging.AppLog
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import com.reporead.android.network.wire.AppLogControllerLine
import com.reporead.android.network.wire.AppLogControllerUploadRequest
import com.reporead.android.network.wireJson
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val APP_LOG_WORK = "upload-app-log"
private const val EXITS_SEEN = "app-exits-seen"

/**
 * Asks for one upload of the phone's stored log lines once there is a network. While one is waiting or running, asking
 * again changes nothing: it sends whatever is stored when it runs.
 */
internal fun requestAppLogUpload(context: Context) {
    val request =
        OneTimeWorkRequestBuilder<AppLogUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF)
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(APP_LOG_WORK, ExistingWorkPolicy.KEEP, request)
}

class AppLogUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RepoReadApplication
        logProcessExits(app.getSystemService(ActivityManager::class.java), File(app.filesDir, EXITS_SEEN))
        return uploadAppLog(app.api, app.sessionStore.read() is StoredSession.Readable, runAttemptCount)
    }
}

/**
 * One upload of the stored lines ([AppLog.batch]), deleted only once the server has logged them, so a lost answer can
 * at most repeat them. Signed out, the server would refuse them, so they wait for the next start after signing in. A
 * batch the route refuses (INVALID_REQUEST, its only refusal) would be refused again, so it is dropped; other failures
 * are tried again as the background sync's are. [attempt] counts from 0.
 */
internal suspend fun uploadAppLog(api: Api, signedIn: Boolean, attempt: Int): Result {
    if (!signedIn) return Result.success()
    val lines = withContext(Dispatchers.IO) { AppLog.batch() } ?: return Result.success()
    if (lines.isNotEmpty()) {
        try {
            api.uploadAppLog(
                AppLogControllerUploadRequest(lines.map { wireJson.decodeFromString<AppLogControllerLine>(it) })
            )
        } catch (failure: ApiException) {
            if (!(failure.status == 400 && failure.code == "INVALID_REQUEST"))
                return if (shouldRetry("App log upload", failure, attempt)) Result.retry() else Result.failure()
            AppLog.warn("App log lines refused; lines=${lines.size} code=${failure.code}")
        }
    }
    AppLog.sent()
    return Result.success()
}

/**
 * Android's record of why this app's processes ended (the app's and its page renderers'), as app lines: ANRs, native
 * crashes and system kills, which no handler in the app sees. ApplicationExitInfo names the process, the reason, the
 * status, the importance, the memory and the description itself; [at] is its time to the millisecond. Each exit is
 * logged once; [lastLoggedExitFile] holds the newest logged exit's time.
 */
internal fun logProcessExits(activityManager: ActivityManager, lastLoggedExitFile: File) {
    val lastLoggedAt = if (lastLoggedExitFile.isFile) lastLoggedExitFile.readText().toLong() else 0L
    val newExits = activityManager.getHistoricalProcessExitReasons(null, 0, 0).filter { it.timestamp > lastLoggedAt }
    for (exit in newExits.sortedBy { it.timestamp }) {
        val message = "Process exited; at=${Instant.ofEpochMilli(exit.timestamp)} $exit"
        if (exit.reason in FAILED_EXITS) AppLog.warn(message) else AppLog.info(message)
    }
    newExits.maxOfOrNull { it.timestamp }?.let { lastLoggedExitFile.writeText(it.toString()) }
}

private val FAILED_EXITS =
    setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
