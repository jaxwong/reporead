package com.reporead.android.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A failed RepoRead request. [status] 0 means the backend was not reached. [code] is the backend's error code. */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

private const val MAX_JSON_BYTES = 8 * 1024 * 1024
/** The backend's own image ceiling is 5 MiB; anything larger here is a contract violation. */
const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** The RepoRead HTTP/JSON contract client. One request per call: no retries or alternate endpoints. */
class Api(private val baseUrl: String, private val accessToken: () -> String?) {
    suspend fun get(path: String): JSONObject = json("GET", path, null) ?: throw unexpected(200)
    suspend fun post(path: String, body: JSONObject? = null): JSONObject = json("POST", path, body) ?: throw unexpected(200)
    suspend fun put(path: String, body: JSONObject): JSONObject = json("PUT", path, body) ?: throw unexpected(200)
    suspend fun patch(path: String, body: JSONObject): JSONObject = json("PATCH", path, body) ?: throw unexpected(200)
    /** The response body, or null for 204 No Content. */
    suspend fun delete(path: String): JSONObject? = json("DELETE", path, null)

    /** Blocking; for callers already on a background thread, such as WebView request interception. */
    fun bytes(path: String): ByteArray = execute("GET", path, null, MAX_IMAGE_BYTES, "*/*") ?: throw unexpected(204)

    private suspend fun json(method: String, path: String, body: JSONObject?): JSONObject? = withContext(Dispatchers.IO) {
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
            accessToken()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 204) return null
            if (status in 200..299) return connection.inputStream.use { readBounded(it, maxBytes) }
            val error = connection.errorStream?.use { readBounded(it, MAX_JSON_BYTES) }
                ?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }
            throw ApiException(status, error?.optString("code")?.ifEmpty { null } ?: "HTTP_$status",
                error?.optString("message")?.ifEmpty { null } ?: "RepoRead's server returned HTTP $status.")
        } catch (error: IOException) {
            throw ApiException(0, "BACKEND_UNAVAILABLE", "RepoRead's server could not be reached (${error.javaClass.simpleName}).")
        } finally {
            connection.disconnect()
        }
    }

    private fun unexpected(status: Int) = ApiException(status, "INVALID_RESPONSE", "RepoRead's server returned an unexpected response.")

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val bytes = stream.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw ApiException(0, "INVALID_RESPONSE", "RepoRead's server response exceeded $maxBytes bytes.")
        return bytes
    }
}

/** Reads a successful response; a missing or mistyped field is a contract violation, not a crash. */
inline fun <T> contract(read: () -> T): T = try {
    read()
} catch (error: JSONException) {
    throw ApiException(200, "INVALID_RESPONSE", "RepoRead's server response did not match the app's contract.")
}

/** A short, user-facing explanation of a failure, distinguishing the states the reader must show explicitly. */
fun ApiException.describe(): String = when (code) {
    "BACKEND_UNAVAILABLE" -> "Can't reach RepoRead's server. Is it running, and is adb reverse set up?"
    "GITHUB_UNAVAILABLE" -> "GitHub is unavailable or rate limited. Nothing was changed."
    "GITHUB_ACCESS_DENIED", "REPOSITORY_NOT_AUTHORIZED" -> "Access denied: $message"
    "UNSUPPORTED_CONTENT" -> "This note can't be shown: $message"
    "DOCUMENT_DELETED" -> "This note was removed from the repository."
    "SIGN_IN_REQUIRED", "HTTP_401" -> "Sign in again to refresh from GitHub."
    "NOT_FOUND" -> "Not found."
    else -> message
}
