package com.reporead.android.sync

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.reporead.android.RepoReadApplication
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.log.AppLog
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * One background run: sends what waits, as library open does, but loads no lists (Sync.syncWaitingChanges). An
 * unreachable server, a 5xx, or GitHub unavailable is tried again by WorkManager; anything else (signed out, access
 * denied) waits for the next change or library open, which show it. [attempt] counts from 0.
 */
internal suspend fun backgroundSync(sync: Sync, attempt: Int): Result {
    val synced =
        try {
            sync.syncWaitingChanges()
        } catch (error: ApiException) {
            return if (triesAgain("Background sync", error, attempt)) Result.retry() else Result.failure()
        }
    if (synced == null) {
        AppLog.info("Background sync: nothing waiting; attempt=$attempt")
        return Result.success()
    }
    synced.notSent?.let {
        return if (triesAgain("Background sync", it, attempt)) Result.retry() else Result.failure()
    }
    AppLog.info("Background sync done; attempt=$attempt refused=${synced.refused.size}")
    return Result.success()
}

/**
 * Whether WorkManager tries [run] again after [error]: only a passing failure (unreachable, a 5xx, GitHub unavailable),
 * and only within [MAX_ATTEMPTS]. WorkManager counts every start as an attempt, also one after Android stopped the run.
 */
private fun triesAgain(run: String, error: ApiException, attempt: Int): Boolean {
    val passing = error.unreachable || error.status >= 500
    val retry = passing && attempt + 1 < MAX_ATTEMPTS
    AppLog.warn("$run failed; attempt=$attempt status=${error.status} code=${error.code} retry=$retry")
    return retry
}

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
 * One upload of the stored lines ([AppLog.upload]). Signed out, the server would refuse them, so they wait for the next
 * start after signing in. Failures are tried again as the background sync's are. [attempt] counts from 0.
 */
internal suspend fun uploadAppLog(api: Api, signedIn: Boolean, attempt: Int): Result {
    if (!signedIn) return Result.success()
    try {
        AppLog.upload(api)
    } catch (error: ApiException) {
        return if (triesAgain("App log upload", error, attempt)) Result.retry() else Result.failure()
    }
    return Result.success()
}

/**
 * Android's record of why this app's processes ended (the app's and its page renderers'), as app lines: ANRs, native
 * crashes and system kills, which no handler in the app sees. Each exit is logged once; [seen] holds the newest logged
 * exit's time.
 */
internal fun logProcessExits(activities: ActivityManager, seen: File) {
    val after = if (seen.isFile) seen.readText().toLong() else 0L
    val exits = activities.getHistoricalProcessExitReasons(null, 0, 0).filter { it.timestamp > after }
    for (exit in exits.sortedBy { it.timestamp }) {
        val line =
            "Process exited; at=${Instant.ofEpochMilli(exit.timestamp)} process=${exit.processName} " +
                "reason=${exitReason(exit.reason)} status=${exit.status} importance=${exit.importance} pssKb=${exit.pss}"
        if (exit.reason in FAILED_EXITS) AppLog.warn(line) else AppLog.info(line)
    }
    exits.maxOfOrNull { it.timestamp }?.let { seen.writeText(it.toString()) }
}

private val FAILED_EXITS =
    setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

private fun exitReason(reason: Int) =
    when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

private fun saveAllName(repositoryId: Long) = "save-all-notes-$repositoryId"

private const val REPOSITORY = "repositoryId"
private const val DONE = "done"
private const val TO_FETCH = "toFetch"
private const val FETCHED = "fetched"
private const val ALREADY_SAVED = "alreadySaved"
private const val CANNOT_SHOW = "cannotShow"
private const val REASON = "reason"
// A UTF-16 unit needs at most 3 UTF-8 bytes; reserve the rest of Data's ceiling for fields and encoding.
private const val MAX_REASON_UNITS = Data.MAX_DATA_BYTES / 4

/**
 * Asks for Save all of [repositoryId] once the phone has a network; it runs whether or not any screen is open. While
 * one is waiting or running, asking again changes nothing.
 */
fun requestSaveAllNotes(context: Context, repositoryId: Long) {
    val request =
        OneTimeWorkRequestBuilder<SaveAllNotesWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF)
            .setInputData(workDataOf(REPOSITORY to repositoryId))
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(saveAllName(repositoryId), ExistingWorkPolicy.KEEP, request)
}

/** Cancels this repository's waiting or running Save all, keeping the complete notes it already saved. */
suspend fun stopSavingNotes(context: Context, repositoryId: Long) {
    WorkManager.getInstance(context).cancelUniqueWork(saveAllName(repositoryId)).await()
}

class SaveAllNotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repositoryId =
            checkNotNull(inputData.keyValueMap[REPOSITORY] as? Long) { "Save all enqueued without a repository id" }
        return saveAllInBackground((applicationContext as RepoReadApplication).sync, repositoryId, runAttemptCount) {
            setProgress(it)
        }
    }
}

/**
 * One Save all run ([Sync.saveAllNotes]), reporting its progress through [report]. A run Android stops part-way (after
 * about 10 minutes, or when the network goes) is started again by WorkManager, and each run fetches only the notes not
 * yet saved. Failures are tried again as the background sync's are; the last says how far it got. [attempt] counts
 * from 0.
 */
internal suspend fun saveAllInBackground(
    sync: Sync,
    repositoryId: Long,
    attempt: Int,
    report: suspend (Data) -> Unit,
): Result {
    var done = 0
    var toFetch = 0
    val saved =
        try {
            sync.saveAllNotes(repositoryId) { doneNow, toFetchNow ->
                done = doneNow
                toFetch = toFetchNow
                report(workDataOf(DONE to done, TO_FETCH to toFetch))
            }
        } catch (error: ApiException) {
            if (triesAgain("Saving all notes", error, attempt)) return Result.retry()
            val reason = error.describe()
            val boundedReason = if (reason.length > MAX_REASON_UNITS) reason.take(MAX_REASON_UNITS) + "…" else reason
            return Result.failure(workDataOf(DONE to done, TO_FETCH to toFetch, REASON to boundedReason))
        }
    return Result.success(
        workDataOf(FETCHED to saved.fetched, ALREADY_SAVED to saved.alreadySaved, CANNOT_SHOW to saved.cannotShow)
    )
}

/** What Save all of one repository is doing, or did last; WorkManager owns the run, and this only reads it. */
sealed interface SavingNotes {
    /** Waiting for a network, for a retry, or for Android to run it. */
    data object Waiting : SavingNotes

    /** [done] of the [toFetch] listed notes checked for pages and images; [toFetch] is 0 until they are known. */
    data class Running(val done: Int, val toFetch: Int) : SavingNotes

    /** [Sync.SavedAll]. */
    data class Saved(val fetched: Int, val alreadySaved: Int, val cannotShow: Int) : SavingNotes

    /**
     * Ended after [done] of [toFetch], those kept; [reason] is null when the run failed in RepoRead itself (a bug, or a
     * full disk), not in a call to its server.
     */
    data class Stopped(val done: Int, val toFetch: Int, val reason: String?) : SavingNotes
}

/** Save all of [repositoryId], as WorkManager runs it; null before the first or after Stop. */
fun savingNotes(context: Context, repositoryId: Long): Flow<SavingNotes?> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(saveAllName(repositoryId)).map { infos ->
        // KEEP replaces a finished run with the new one, so a name holds at most one.
        check(infos.size <= 1) { "Save all of repository $repositoryId has ${infos.size} runs" }
        infos.singleOrNull()?.let(::savingNotes)
    }

internal fun savingNotes(info: WorkInfo): SavingNotes? =
    when (info.state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> SavingNotes.Waiting
        WorkInfo.State.RUNNING -> SavingNotes.Running(info.progress.getInt(DONE, 0), info.progress.getInt(TO_FETCH, 0))
        WorkInfo.State.SUCCEEDED ->
            info.outputData.let {
                SavingNotes.Saved(it.getInt(FETCHED, 0), it.getInt(ALREADY_SAVED, 0), it.getInt(CANNOT_SHOW, 0))
            }
        WorkInfo.State.FAILED ->
            info.outputData.let {
                SavingNotes.Stopped(it.getInt(DONE, 0), it.getInt(TO_FETCH, 0), it.getString(REASON))
            }
        WorkInfo.State.CANCELLED -> null
    }
