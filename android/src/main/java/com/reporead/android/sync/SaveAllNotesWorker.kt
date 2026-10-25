// Owns: the queued prefetch of every saved page and its progress projection.

package com.reporead.android.sync

import android.content.Context
import android.database.sqlite.SQLiteFullException
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
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.ApiException
import com.reporead.android.network.describe
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private fun saveAllName(repositoryId: Long) = "save-all-notes-$repositoryId"

private const val REPOSITORY = "repositoryId"
private const val DONE = "done"
private const val TOTAL_NOTES = "toFetch"
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
        return saveAllInBackground(
            (applicationContext as RepoReadApplication).phoneSync,
            repositoryId,
            runAttemptCount,
        ) {
            setProgress(it)
        }
    }
}

/**
 * One Save all run ([PhoneSync.saveAllNotes]), reporting its progress through [report]. A run Android stops part-way
 * (after about 10 minutes, or when the network goes) is started again by WorkManager, and each run fetches only the
 * notes not yet saved. Failures are tried again as the background sync's are; the last says how far it got. A write the
 * phone's storage refuses ends the run at once, saying so. [attempt] counts from 0.
 */
internal suspend fun saveAllInBackground(
    sync: PhoneSync,
    repositoryId: Long,
    attempt: Int,
    report: suspend (Data) -> Unit,
): Result {
    var done = 0
    var totalNotes = 0
    val saveResult =
        try {
            sync.saveAllNotes(repositoryId) { doneNow, totalNotesNow ->
                done = doneNow
                totalNotes = totalNotesNow
                report(workDataOf(DONE to done, TOTAL_NOTES to totalNotes))
            }
        } catch (failure: ApiException) {
            if (shouldRetry("Saving all notes", failure, attempt)) return Result.retry()
            val reason = failure.describe()
            val boundedReason = if (reason.length > MAX_REASON_UNITS) reason.take(MAX_REASON_UNITS) + "…" else reason
            return Result.failure(workDataOf(DONE to done, TOTAL_NOTES to totalNotes, REASON to boundedReason))
        } catch (failure: Exception) {
            // Api turns network I/O into ApiException, so an IOException here is an image file's write.
            if (failure !is SQLiteFullException && failure !is IOException) throw failure
            DeviceLog.warn(
                "Saving all notes stopped; storage write failed repositoryId=$repositoryId done=$done",
                failure,
            )
            return Result.failure(workDataOf(DONE to done, TOTAL_NOTES to totalNotes, REASON to STORAGE_FAILED))
        }
    return Result.success(
        workDataOf(
            FETCHED to saveResult.fetched,
            ALREADY_SAVED to saveResult.alreadySaved,
            CANNOT_SHOW to saveResult.cannotShow,
        )
    )
}

/** A saved page (Room) or image file that could not be written; not retried, since a full disk stays full. */
private const val STORAGE_FAILED = "RepoRead couldn't write to this phone's storage; it may be full."

/** What Save all of one repository is doing, or did last; WorkManager owns the run, and this only reads it. */
sealed interface SavingNotes {
    /** Waiting for a network, for a retry, or for Android to run it. */
    data object Waiting : SavingNotes

    /** [done] of the [totalNotes] listed notes checked for pages and images; [totalNotes] is 0 until they are known. */
    data class Running(val done: Int, val totalNotes: Int) : SavingNotes

    /** [PhoneSync.SaveAllResult]. */
    data class Saved(val fetched: Int, val alreadySaved: Int, val cannotShow: Int) : SavingNotes

    /**
     * Ended after [done] of [totalNotes], those kept; [reason] says why (its server's answer, or the phone's storage),
     * and is null when the run failed in RepoRead itself (a bug).
     */
    data class Stopped(val done: Int, val totalNotes: Int, val reason: String?) : SavingNotes
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
        WorkInfo.State.RUNNING ->
            SavingNotes.Running(info.progress.getInt(DONE, 0), info.progress.getInt(TOTAL_NOTES, 0))
        WorkInfo.State.SUCCEEDED ->
            info.outputData.let {
                SavingNotes.Saved(it.getInt(FETCHED, 0), it.getInt(ALREADY_SAVED, 0), it.getInt(CANNOT_SHOW, 0))
            }
        WorkInfo.State.FAILED ->
            info.outputData.let {
                SavingNotes.Stopped(it.getInt(DONE, 0), it.getInt(TOTAL_NOTES, 0), it.getString(REASON))
            }
        WorkInfo.State.CANCELLED -> null
    }
