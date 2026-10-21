// Owns: The app's log lines: logcat and the bounded store on the phone that the upload worker sends from.

package com.reporead.android.logging

import android.content.Context
import android.util.Log
import com.reporead.android.BuildConfig
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "RepoRead"
private const val PENDING = "pending.jsonl"
private const val SENDING = "sending.jsonl"

/**
 * One upload carries a whole batch, so the store is at most half the server's request body cap
 * (RequestBodyLimit.MAX_BYTES): the lines plus the request's commas and fields always fit.
 */
private val STORE_MAX_BYTES = BuildConfig.REQUEST_MAX_BYTES / 2

/** Even escaped at 6 JSON bytes a unit, one line fits in what trimming keeps, so the newest line is never dropped. */
internal const val MAX_LINE_CHARS = 8_000
private const val MAX_FRAMES = 40
private const val MAX_CAUSES = 4

/** Where RepoReadApplication keeps the store. */
internal fun appLogDirectory(context: Context) = File(context.filesDir, "app-log")

/**
 * The app's one logger. Every line goes to logcat (tag RepoRead) and, unless it is [debug], to a store on the phone
 * that the upload worker (sync/BackgroundSync.kt) sends to the server's log, so the phone's lines are read on the
 * server without adb. The app's own lines name ids, codes and counts; a throwable's messages and the reader page's
 * console output can quote a note, and are kept as they are (ADR-21, 2026-10-21: the one user's own server log may hold
 * note text). When the store outgrows its cap, its oldest lines go.
 *
 * Each stored line is one AppLogControllerLine as JSON; the worker decodes them with the generated model, so a field
 * written wrong here fails there.
 */
object AppLog {
    private val lock = Any()
    @Volatile private var directory: File? = null

    /** RepoReadApplication calls this before anything logs, with [appLogDirectory]. */
    fun start(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the app log folder $directory" }
        this.directory = directory
    }

    /** A line too frequent to keep, such as one per scroll stop: logcat only, never stored or sent to the server. */
    fun debug(message: String) {
        Log.d(TAG, message)
    }

    fun info(message: String) {
        Log.i(TAG, message)
        store("INFO", message)
    }

    fun warn(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        store("WARN", if (error == null) message else "$message\n${trace(error)}")
    }

    /**
     * The uncaught-exception handler's line, stored before the process ends; Android logs the trace to logcat itself.
     */
    fun crash(thread: Thread, error: Throwable) {
        store("ERROR", "Crash; thread=${thread.name}\n${trace(error)}")
    }

    /**
     * The stored lines to send, oldest first: the batch a failed send left, or else everything stored so far, which
     * then stops growing (later lines start a new store). Null when nothing is stored. The batch stays until [sent].
     */
    fun batch(): List<String>? =
        synchronized(lock) {
            val sending = File(started(), SENDING)
            val pending = File(started(), PENDING)
            if (!sending.exists() && pending.exists())
                check(pending.renameTo(sending)) { "Cannot move $pending to $sending" }
            if (sending.exists()) complete(sending.readText()) else null
        }

    /** The last [batch] reached the server, or was refused for good: it goes. */
    fun sent() =
        synchronized(lock) {
            val sending = File(started(), SENDING)
            check(sending.delete()) { "Cannot delete the sent app log $sending" }
        }

    private fun started() = checkNotNull(directory) { "AppLog.start was not called" }

    private fun store(level: String, message: String) {
        val bounded =
            if (message.length <= MAX_LINE_CHARS) message
            else message.take(MAX_LINE_CHARS) + "… (${message.length - MAX_LINE_CHARS} characters cut)"
        val pending = File(started(), PENDING)
        synchronized(lock) {
            try {
                pending.appendText(line(level, bounded))
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
        for (line in lines.asReversed()) {
            val size = line.encodeToByteArray().size + 1
            if (bytes + size > STORE_MAX_BYTES / 2) break
            kept.addFirst(line)
            bytes += size
        }
        val dropped = line("WARN", "App log store full; dropped=${lines.size - kept.size} oldest lines")
        val trimmed = File(pending.parentFile, "$PENDING.trimmed")
        trimmed.writeText(dropped + kept.joinToString("") { "$it\n" })
        check(trimmed.renameTo(pending)) { "Cannot replace $pending with $trimmed" }
    }
}

/** One stored line, with this build's version: an updated app sends the lines the previous version left. */
private fun line(level: String, message: String) =
    buildJsonObject {
        put("at", Instant.now().toString())
        put("appVersion", BuildConfig.VERSION_NAME)
        put("level", level)
        put("message", message)
    }
        .toString() + "\n"

/** The stored lines, without a last one the process ended while writing (it has no newline). */
private fun complete(text: String): List<String> = text.split('\n').dropLast(1).filter { it.isNotEmpty() }

/** A throwable's classes, messages and frames, causes included, bounded as [MAX_FRAMES] and [MAX_CAUSES] say. */
private fun trace(error: Throwable): String = buildString {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth <= MAX_CAUSES) {
        if (depth > 0) append("\nCaused by: ")
        append(current)
        current.stackTrace.take(MAX_FRAMES).forEach { append("\n\tat ").append(it) }
        if (current.stackTrace.size > MAX_FRAMES) append("\n\t... ${current.stackTrace.size - MAX_FRAMES} more")
        current = current.cause
        depth++
    }
}
