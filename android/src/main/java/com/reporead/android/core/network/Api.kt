package com.reporead.android.core.network

import android.util.Log
import com.reporead.android.BuildConfig
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A failed RepoRead request. [status] is the HTTP status of the server's answer, or 0 when there was none: it was not
 * reached, or nothing was sent. [code] is the backend's error code.
 */
class ApiException(val status: Int, val code: String, override val message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    /** The server did not answer, so no other request can reach it now either; a later try may. */
    val unreachable: Boolean
        get() = code == "BACKEND_UNAVAILABLE"
}

private const val MAX_JSON_BYTES = 8 * 1024 * 1024
/** The backend's own image ceiling is 5 MiB; anything larger here is a contract violation. */
const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/**
 * The RepoRead HTTP/JSON contract client: one request per call, no retries. Any 401 hands [onUnauthorized] the refused
 * session.
 */
class Api(
    private val baseUrl: String,
    private val accessToken: () -> String?,
    private val onUnauthorized: (sent: String?) -> Unit,
) {
    suspend fun get(path: String): JSONObject = json("GET", path, null) ?: throw unexpected(200)

    suspend fun post(path: String, body: JSONObject? = null): JSONObject =
        json("POST", path, body) ?: throw unexpected(200)

    suspend fun put(path: String, body: JSONObject? = null): JSONObject =
        json("PUT", path, body) ?: throw unexpected(200)

    suspend fun patch(path: String, body: JSONObject): JSONObject = json("PATCH", path, body) ?: throw unexpected(200)

    /** The response body, or null for 204 No Content. */
    suspend fun delete(path: String): JSONObject? = json("DELETE", path, null)

    /** Blocking; for callers already on a background thread, such as WebView request interception. */
    fun bytes(path: String): ByteArray = execute("GET", path, null, MAX_IMAGE_BYTES, "*/*") ?: throw unexpected(204)

    private suspend fun json(method: String, path: String, body: JSONObject?): JSONObject? =
        withContext(Dispatchers.IO) {
            val bytes = execute(method, path, body, MAX_JSON_BYTES, "application/json") ?: return@withContext null
            try {
                JSONObject(bytes.toString(Charsets.UTF_8))
            } catch (error: JSONException) {
                throw unexpected(200)
            }
        }

    private fun execute(method: String, path: String, body: JSONObject?, maxBytes: Int, accept: String): ByteArray? {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            // The backend may spend up to its own 15 s GitHub timeout before rendering.
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            val token = accessToken()
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 204) return null
            if (status in 200..299) return connection.inputStream.use { readBounded(it, maxBytes, status) }
            if (status == 401) onUnauthorized(token)
            // A body that is not RepoRead's {code, message} gets code HTTP_<status>, never read as RepoRead's answer.
            val error =
                connection.errorStream
                    ?.use { readBounded(it, MAX_JSON_BYTES, status) }
                    ?.let { bytes ->
                        try {
                            JSONObject(bytes.toString(Charsets.UTF_8))
                        } catch (unreadable: JSONException) {
                            Log.w(
                                "RepoRead",
                                "Error response is not RepoRead's JSON; method=$method path=$path status=$status",
                            )
                            null
                        }
                    }
            throw ApiException(
                status,
                error?.optString("code")?.ifEmpty { null } ?: "HTTP_$status",
                error?.optString("message")?.ifEmpty { null } ?: "RepoRead's server returned HTTP $status.",
            )
        } catch (error: IOException) {
            throw ApiException(
                0,
                "BACKEND_UNAVAILABLE",
                "RepoRead's server could not be reached (${error.javaClass.simpleName}).",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun unexpected(status: Int) =
        ApiException(status, "INVALID_RESPONSE", "RepoRead's server returned an unexpected response.")

    private fun readBounded(stream: InputStream, maxBytes: Int, status: Int): ByteArray {
        val bytes = stream.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes)
            throw ApiException(status, "INVALID_RESPONSE", "RepoRead's server response exceeded $maxBytes bytes.")
        return bytes
    }
}

/**
 * Reads a successful response; a missing or mistyped field, or a malformed time, is a contract violation, not a crash.
 * The cause is kept but not logged: its message can quote the response, and so a note's text.
 */
inline fun <T> contract(read: () -> T): T =
    try {
        read()
    } catch (error: JSONException) {
        throw contractBroken(error)
    } catch (error: DateTimeParseException) {
        throw contractBroken(error)
    }

/**
 * The text field [name] of a response. Not getString: Android's reads a JSON null as the text "null" and a number as
 * its digits, which would pass the contract as data.
 */
fun JSONObject.string(name: String): String = get(name) as? String ?: throw JSONException("$name is not text")

fun JSONArray.string(index: Int): String = get(index) as? String ?: throw JSONException("Item $index is not text")

@PublishedApi
internal fun contractBroken(cause: Exception) =
    ApiException(200, "INVALID_RESPONSE", "RepoRead's server response did not match the app's contract.", cause)

/**
 * A short, user-facing explanation of a failure, distinguishing the states the reader must show explicitly. The one
 * place that words a code for this app's screens; the server's message, which names no screen, is the fallback.
 */
fun ApiException.describe(): String =
    when (code) {
        "BACKEND_UNAVAILABLE" ->
            if (BuildConfig.DEBUG) "Can't reach RepoRead's server. Is it running, and is adb reverse set up?"
            else "Can't reach RepoRead's server. Check your internet connection."
        "GITHUB_UNAVAILABLE" -> "GitHub is unavailable or rate limited. Nothing was changed."
        "GITHUB_ACCESS_DENIED",
        "REPOSITORY_NOT_AUTHORIZED" -> "Access denied: $message"
        "UNSUPPORTED_CONTENT" -> "This note can't be shown: $message"
        "DOCUMENT_DELETED" -> "This note was removed from the repository."
        "DEFAULT_BRANCH_NOT_FOUND" ->
            "The default branch was not found: the repository may be empty or no longer accessible, or its default " +
                "branch was renamed (then tap Update on it under Add repository)."
        "REPOSITORY_RENAMED" ->
            "This repository or its default branch was renamed on GitHub; tap Update on it under Add repository."
        "SYNC_SUPERSEDED" ->
            "This repository changed during the refresh, so nothing was replaced; refresh again to get the latest notes."
        "ANNOTATION_CONFLICT" -> "It was changed elsewhere; sync, reopen the note, and try again."
        "ACCOUNT_MISMATCH" ->
            "This phone holds another account's notes, so it signs in only as that account. Sign in with the GitHub " +
                "account that saved them, or sign out first to remove them from this phone."
        "SIGN_IN_REQUIRED",
        "HTTP_401" -> "Sign in again to refresh from GitHub."
        "NOT_FOUND" -> "Not found."
        else -> message
    }
