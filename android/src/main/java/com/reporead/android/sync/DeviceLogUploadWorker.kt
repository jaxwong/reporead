// Owns: the queued upload of durable Android log batches and process-exit checkpoints.

package com.reporead.android.sync

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.reporead.android.RepoReadApplication
import com.reporead.android.auth.StoredSession
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import com.reporead.android.network.wire.DeviceLogIngestControllerUploadRequest
import com.reporead.android.network.wire.DeviceLogLine
import com.reporead.android.network.wireJson
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEVICE_LOG_WORK = "upload-app-log"
private const val EXITS_SEEN = "app-exits-seen"

/**
 * Asks for one upload of the phone's stored log lines after [delay], once there is a network. While one is waiting or
 * running, asking again changes nothing: it sends whatever is stored when it runs. The returned operation completes
 * once WorkManager has recorded the request, which then outlives this process.
 *
 * A failed upload is never retried on a backoff, which would hold every later request (a crash's too) for up to hours:
 * the next request sends its lines. DeviceLog does not request another run for warnings stored while a batch is
 * claimed.
 */
internal fun requestDeviceLogUpload(context: Context, delay: Duration = Duration.ZERO): Operation {
    val request =
        OneTimeWorkRequestBuilder<DeviceLogUploadWorker>()
            .setInitialDelay(delay)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    return WorkManager.getInstance(context).enqueueUniqueWork(DEVICE_LOG_WORK, ExistingWorkPolicy.KEEP, request)
}

class DeviceLogUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RepoReadApplication
        logProcessExits(app.getSystemService(ActivityManager::class.java), File(app.filesDir, EXITS_SEEN))
        return uploadDeviceLogBatches(app.api, app.sessionStore.read() is StoredSession.Readable)
    }
}

/**
 * At most two uploads of the stored lines ([DeviceLog.claimPendingBatch]): the batch a failed run left, then the lines
 * stored since, so a warning logged after a failure does not wait behind that batch for another request. Each batch is
 * completed only once the server has logged it, so a lost answer can at most repeat it. Signed out, the server would
 * refuse them, so they wait for the next start after signing in. A batch the route refuses (INVALID_REQUEST, its only
 * refusal) would be refused again, so it is dropped; after any other failure the batch waits for the next request
 * ([requestDeviceLogUpload]).
 */
internal suspend fun uploadDeviceLogBatches(api: Api, signedIn: Boolean): Result {
    if (!signedIn) return Result.success()
    repeat(2) {
        val lines = withContext(Dispatchers.IO) { DeviceLog.claimPendingBatch() } ?: return Result.success()
        if (lines.isNotEmpty()) {
            try {
                api.uploadDeviceLogBatch(
                    DeviceLogIngestControllerUploadRequest(lines.map { wireJson.decodeFromString<DeviceLogLine>(it) })
                )
            } catch (failure: ApiException) {
                if (!(failure.status == 400 && failure.code == "INVALID_REQUEST")) {
                    DeviceLog.warn(
                        "Device log upload failed; lines=${lines.size} status=${failure.status} code=${failure.code}"
                    )
                    return Result.failure()
                }
                DeviceLog.warn("Device log lines refused; lines=${lines.size} code=${failure.code}")
            }
        }
        DeviceLog.finishClaimedBatch()
    }
    return Result.success()
}

/**
 * Android's record of why this app's processes ended (the app's and its page renderers'), as device-log lines: ANRs,
 * native crashes and system kills, which no handler in the app sees. ApplicationExitInfo names the process, the reason,
 * the status, the importance, the memory and the description itself; [at] is its time to the millisecond. Each exit is
 * logged once; [lastLoggedExitFile] holds the newest logged exit's time.
 */
internal fun logProcessExits(activityManager: ActivityManager, lastLoggedExitFile: File) {
    val lastLoggedAt = if (lastLoggedExitFile.isFile) lastLoggedExitFile.readText().toLong() else 0L
    val newExits = activityManager.getHistoricalProcessExitReasons(null, 0, 0).filter { it.timestamp > lastLoggedAt }
    for (exit in newExits.sortedBy { it.timestamp }) {
        val message = "Process exited; at=${Instant.ofEpochMilli(exit.timestamp)} $exit"
        if (exit.reason in FAILED_EXITS) DeviceLog.warn(message) else DeviceLog.info(message)
    }
    newExits.maxOfOrNull { it.timestamp }?.let { lastLoggedExitFile.writeText(it.toString()) }
}

private val FAILED_EXITS =
    setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
