// Owns: The app's log lines: logcat, the bounded store on the phone, and sending that store to the server's log.

package com.reporead.android.core.log

import android.util.Log
import com.reporead.android.BuildConfig
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.wire.AppLogControllerLine
import com.reporead.android.core.network.wire.AppLogControllerUploadRequest
import com.reporead.android.core.network.wireJson
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

private const val TAG = "RepoRead"
private const val PENDING = "pending.jsonl"
private const val SENDING = "sending.jsonl"

/**
 * One upload carries the whole store, so the store is at most half the server's request body cap
 * (RequestBodyLimit.MAX_BYTES): the lines plus the request's commas and fields always fit.
 */
private val STORE_MAX_BYTES = BuildConfig.REQUEST_MAX_BYTES / 2

/** Even escaped at 6 JSON bytes a unit, one line fits in what trimming keeps, so the newest line is never dropped. */
internal const val MAX_LINE_CHARS = 8_000
private const val MAX_FRAMES = 40
private const val MAX_CAUSES = 4

/**
 * The app's one logger. Every line goes to logcat (tag RepoRead) and to a store on the phone that [upload] sends to the
 * server's log (AppLogController), so the phone's lines are read on the server without adb. Lines name ids, codes and
 * counts, never note text (ADR-21). A throwable's message is free text that can quote a note, so the stored copy keeps
 * only its types and frames; logcat keeps the whole throwable. When the store outgrows its cap, its oldest lines go.
 */
object AppLog {
    private val lock = Any()
    private val uploading = Mutex()
    @Volatile private var directory: File? = null

    /** RepoReadApplication calls this before anything logs; JVM tests call it with a temporary folder. */
    fun start(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the app log folder $directory" }
        this.directory = directory
    }

    fun info(message: String) {
        Log.i(TAG, message)
        store(AppLogControllerLine.Level.INFO, message)
    }

    fun warn(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        store(AppLogControllerLine.Level.WARN, if (error == null) message else "$message\n${frames(error)}")
    }

    /** The uncaught-exception handler's line, stored before the process ends; Android logs the full trace itself. */
    fun crash(thread: Thread, error: Throwable) {
        store(AppLogControllerLine.Level.ERROR, "Crash; thread=${thread.name}\n${frames(error)}")
    }

    /**
     * Sends every stored line in one request and deletes them only once the server has logged them, so a lost answer
     * can at most repeat them. A batch the route refuses (INVALID_REQUEST, its only refusal) would be refused again, so
     * it is dropped; any other failure keeps it for the next upload and is rethrown. Lines logged meanwhile wait for
     * the next upload.
     */
    suspend fun upload(api: Api) = uploading.withLock {
        val sending = File(started(), SENDING)
        synchronized(lock) {
            val pending = File(started(), PENDING)
            if (!sending.exists() && pending.exists())
                check(pending.renameTo(sending)) { "Cannot move $pending to $sending" }
        }
        if (!sending.exists()) return@withLock
        val lines = withContext(Dispatchers.IO) { complete(sending.readText()) }
        if (lines.isNotEmpty()) {
            try {
                api.uploadAppLog(AppLogControllerUploadRequest(lines))
            } catch (error: ApiException) {
                if (!(error.status == 400 && error.code == "INVALID_REQUEST")) throw error
                warn("App log lines refused; lines=${lines.size} code=${error.code}")
            }
        }
        check(sending.delete()) { "Cannot delete the sent app log $sending" }
    }

    private fun started() = checkNotNull(directory) { "AppLog.start was not called" }

    private fun store(level: AppLogControllerLine.Level, message: String) {
        val bounded =
            if (message.length <= MAX_LINE_CHARS) message
            else message.take(MAX_LINE_CHARS) + "… (${message.length - MAX_LINE_CHARS} characters cut)"
        // Stored with this build's version: an updated app sends the lines the previous version left.
        val line =
            wireJson.encodeToString(
                AppLogControllerLine(
                    at = Instant.now(),
                    appVersion = BuildConfig.VERSION_NAME,
                    level = level,
                    message = bounded,
                )
            ) + "\n"
        val pending = File(started(), PENDING)
        synchronized(lock) {
            try {
                pending.appendText(line)
                if (pending.length() > STORE_MAX_BYTES) keepNewest(pending)
            } catch (error: IOException) {
                // Logcat already has the line; this one is lost to the server.
                Log.w(TAG, "App log line not stored; error=${error.javaClass.simpleName}")
            }
        }
    }

    /** Keeps the newest lines within half the cap, so trimming is rare, and says how many older lines went. */
    private fun keepNewest(pending: File) {
        val lines = complete(pending.readText())
        val kept = ArrayDeque<String>()
        var bytes = 0
        for (line in lines.asReversed().map { wireJson.encodeToString(it) + "\n" }) {
            val size = line.encodeToByteArray().size
            if (bytes + size > STORE_MAX_BYTES / 2) break
            kept.addFirst(line)
            bytes += size
        }
        val dropped =
            AppLogControllerLine(
                at = Instant.now(),
                appVersion = BuildConfig.VERSION_NAME,
                level = AppLogControllerLine.Level.WARN,
                message = "App log store full; dropped=${lines.size - kept.size} oldest lines",
            )
        val trimmed = File(pending.parentFile, "$PENDING.trimmed")
        trimmed.writeText(wireJson.encodeToString(dropped) + "\n" + kept.joinToString(""))
        check(trimmed.renameTo(pending)) { "Cannot replace $pending with $trimmed" }
    }
}

/** The stored lines, without a last one the process ended while writing (it has no newline). */
private fun complete(text: String): List<AppLogControllerLine> =
    text.split('\n').dropLast(1).filter { it.isNotEmpty() }.map { wireJson.decodeFromString(it) }

/** A throwable's class names and frames, causes included, without the messages. */
private fun frames(error: Throwable): String = buildString {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth <= MAX_CAUSES) {
        if (depth > 0) append("\nCaused by: ")
        append(current.javaClass.name)
        current.stackTrace.take(MAX_FRAMES).forEach { append("\n\tat ").append(it) }
        if (current.stackTrace.size > MAX_FRAMES) append("\n\t... ${current.stackTrace.size - MAX_FRAMES} more")
        current = current.cause
        depth++
    }
}
