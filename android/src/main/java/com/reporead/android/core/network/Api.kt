// Owns: Named backend routes, bounded HTTP, response correspondence and transport failures; not sync disposition.

package com.reporead.android.core.network

import android.util.Log
import com.reporead.android.BuildConfig
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.wire.AccountDeletionDeleted
import com.reporead.android.core.network.wire.AnnotationControllerAnnotationList
import com.reporead.android.core.network.wire.AnnotationControllerCheckRequest
import com.reporead.android.core.network.wire.AnnotationControllerCreateRequest
import com.reporead.android.core.network.wire.AnnotationControllerEditRequest
import com.reporead.android.core.network.wire.AnnotationControllerReattachRequest
import com.reporead.android.core.network.wire.AnnotationsAnnotation
import com.reporead.android.core.network.wire.AppAuthControllerTokenRequest
import com.reporead.android.core.network.wire.AppSessionsIssuedSession
import com.reporead.android.core.network.wire.BookmarksBookmark
import com.reporead.android.core.network.wire.ConnectionDataCounts
import com.reporead.android.core.network.wire.ConnectionDataDisconnected
import com.reporead.android.core.network.wire.DocumentControllerContent
import com.reporead.android.core.network.wire.LibrarySnapshot
import com.reporead.android.core.network.wire.ReadingControllerSaveRequest
import com.reporead.android.core.network.wire.ReadingStatesState
import com.reporead.android.core.network.wire.RepositoryConnectionsConnection
import com.reporead.android.core.network.wire.RepositoryControllerAvailableList
import com.reporead.android.core.network.wire.RepositoryControllerConnectRequest
import com.reporead.android.core.network.wire.RepositorySyncResult
import com.reporead.android.core.network.wire.ReviewControllerGradeRequest
import com.reporead.android.core.network.wire.ReviewsReview
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Owns a failed request's status, code and underlying cause. [status] is the response status, or 0 when no complete
 * transport answer was available or nothing was sent. [code] is the backend's code or a local failure classification.
 */
class ApiException(val status: Int, val code: String, override val message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    /** The server did not answer, so no other request can reach it now either; a later try may. */
    val unreachable: Boolean
        get() = code == "BACKEND_UNAVAILABLE"
}

/**
 * The error envelope (ApiErrors.Body), the one wire shape not generated: the spec publishes route answers, not the
 * advice that wraps every failure, and the app reads [code] as text because a newer server may send codes this version
 * does not know. Only known codes authorize anything (Sync.refusesItem).
 */
@Serializable
private data class Failure(val code: String, val message: String) {
    init {
        require(code.isNotBlank()) { "Expected a non-blank failure code" }
    }
}

private const val MAX_JSON_BYTES = 8 * 1024 * 1024
/** The backend's own image ceiling is 5 MiB; anything larger here is a contract violation. */
const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** One request per public route call, no retries; only this class can execute a backend path. */
class Api(
    private val baseUrl: String,
    private val session: () -> StoredSession,
    private val onUnauthorized: (sent: String?) -> Unit,
) {
    suspend fun library(): LibrarySnapshot =
        json("GET", "/api/library", maxBytes = BuildConfig.LIBRARY_MAX_RESPONSE_BYTES)

    suspend fun exchangeToken(request: AppAuthControllerTokenRequest): AppSessionsIssuedSession =
        json("POST", "/api/app-auth/token", wireJson.encodeToString(request))

    suspend fun revokeSession() = noContent("DELETE", "/api/app-auth/session")

    suspend fun deleteAccount(): AccountDeletionDeleted = json("DELETE", "/api/account")

    suspend fun availableRepositories(): RepositoryControllerAvailableList = json("GET", "/api/repositories/available")

    suspend fun connectRepository(
        githubRepositoryId: Long,
        request: RepositoryControllerConnectRequest,
    ): RepositoryConnectionsConnection =
        json("POST", "/api/repositories/$githubRepositoryId/connect", wireJson.encodeToString(request)) {
            it.githubRepositoryId == githubRepositoryId
        }

    suspend fun syncRepository(id: Long): RepositorySyncResult =
        json("POST", "/api/repositories/$id/sync") { it.repositoryId == id }

    suspend fun storedData(id: Long): ConnectionDataCounts = json("GET", "/api/repositories/$id/stored-data")

    suspend fun disconnectRepository(id: Long): ConnectionDataDisconnected = json("DELETE", "/api/repositories/$id")

    suspend fun noteContent(id: Long): DocumentControllerContent =
        json("GET", "/api/documents/$id/content") { it.documentId == id }

    suspend fun saveReading(id: Long, request: ReadingControllerSaveRequest): ReadingStatesState =
        json("PUT", "/api/documents/$id/reading-state", wireJson.encodeToString(request)) { it.documentId == id }

    suspend fun setBookmark(id: Long): BookmarksBookmark =
        json("PUT", "/api/documents/$id/bookmark") { it.documentId == id }

    suspend fun clearBookmark(id: Long) = noContent("DELETE", "/api/documents/$id/bookmark")

    suspend fun annotations(id: Long): AnnotationControllerAnnotationList =
        json("GET", "/api/documents/$id/annotations") { it.annotations.all { row -> row.documentId == id } }

    suspend fun createAnnotation(id: Long, request: AnnotationControllerCreateRequest): AnnotationsAnnotation =
        json("POST", "/api/documents/$id/annotations", wireJson.encodeToString(request), statuses = setOf(200, 201)) {
            it.documentId == id && it.mutationId == request.mutationId
        }

    suspend fun editAnnotation(id: Long, request: AnnotationControllerEditRequest): AnnotationsAnnotation =
        json("PATCH", "/api/annotations/$id", wireJson.encodeToString(request)) { it.id == id }

    suspend fun deleteAnnotation(id: Long, expectedVersion: Int) =
        noContent("DELETE", "/api/annotations/$id?expectedVersion=$expectedVersion")

    suspend fun reattachAnnotation(id: Long, request: AnnotationControllerReattachRequest): AnnotationsAnnotation =
        json("POST", "/api/annotations/$id/reattach", wireJson.encodeToString(request)) { it.id == id }

    suspend fun checkCard(id: Long, request: AnnotationControllerCheckRequest): AnnotationsAnnotation =
        json("POST", "/api/annotations/$id/check", wireJson.encodeToString(request)) { it.id == id }

    suspend fun recordReview(id: Long, request: ReviewControllerGradeRequest): ReviewsReview =
        json("POST", "/api/annotations/$id/reviews", wireJson.encodeToString(request)) {
            it.mutationId == request.mutationId && it.grade == request.grade && it.blobSha == request.blobSha
        }

    /** Blocking for WebView resource interception, which already runs on a background thread. */
    fun noteImage(id: Long, commitSha: String, path: String): ByteArray =
        execute(
                "GET",
                "/api/documents/$id/image?commitSha=${query(commitSha)}&path=${query(path)}",
                null,
                MAX_IMAGE_BYTES,
                "*/*",
                setOf(200),
            )
            .second

    private fun query(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** Publishes an answer only after bounded transport, decoding and request correspondence have all succeeded. */
    private suspend inline fun <reified T : Any> json(
        method: String,
        path: String,
        body: String? = null,
        statuses: Set<Int> = setOf(200),
        maxBytes: Int = MAX_JSON_BYTES,
        crossinline matches: (T) -> Boolean = { true },
    ): T =
        withContext(Dispatchers.IO) {
            val (status, bytes) = execute(method, path, body, maxBytes, "application/json", statuses)
            val answer = decodeResponse<T>(bytes.toString(Charsets.UTF_8), status)
            if (!matches(answer)) throw invalidResponse(status)
            answer
        }

    private suspend fun noContent(method: String, path: String): Unit =
        withContext(Dispatchers.IO) {
            execute(method, path, null, MAX_JSON_BYTES, "application/json", setOf(204))
            Unit
        }

    /** The sole HTTP boundary: one attempt, token-aware 401 handling and no durable partial response. */
    private fun execute(
        method: String,
        path: String,
        body: String?,
        maxBytes: Int,
        accept: String,
        statuses: Set<Int>,
    ): Pair<Int, ByteArray> {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        var status = 0
        val route = path.substringBefore('?')
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            // The backend may spend up to its own 15 s GitHub timeout before rendering.
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            val token = bearerToken()
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            status = connection.responseCode
            if (status in 200..299) {
                if (status !in statuses) throw invalidResponse(status)
                return status to
                    if (status == 204) byteArrayOf()
                    else connection.inputStream.use { readBounded(it, maxBytes, status) }
            }
            if (status == 401) onUnauthorized(token)
            val error =
                connection.errorStream
                    ?.use { readBounded(it, MAX_JSON_BYTES, status) }
                    ?.let { bytes ->
                        try {
                            wireJson.decodeFromString<Failure>(bytes.toString(Charsets.UTF_8))
                        } catch (unreadable: IllegalArgumentException) {
                            Log.w(
                                "RepoRead",
                                "Error response is not RepoRead's JSON; method=$method path=$route status=$status",
                            )
                            null
                        }
                    }
            throw ApiException(
                status,
                error?.code ?: "HTTP_$status",
                error?.message ?: "RepoRead's server returned HTTP $status.",
            )
        } catch (error: IOException) {
            Log.w(
                "RepoRead",
                "Backend I/O failed; method=$method path=$route status=$status error=${error.javaClass.simpleName}",
            )
            throw ApiException(
                0,
                "BACKEND_UNAVAILABLE",
                "RepoRead's server could not be reached (${error.javaClass.simpleName}).",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    /** An unreadable session fails before anything is sent, without clearing or replacing it. */
    private fun bearerToken(): String? =
        when (val stored = session()) {
            StoredSession.Absent,
            StoredSession.PermanentlyInvalid -> null
            is StoredSession.Readable -> stored.token
            is StoredSession.TemporarilyUnavailable ->
                throw ApiException(
                    0,
                    "SESSION_UNREADABLE",
                    "The stored session could not be decrypted (${stored.cause.javaClass.simpleName}).",
                    stored.cause,
                )
        }

    /** Reads one byte past the cap to distinguish an exact-limit complete body from an oversized one. */
    private fun readBounded(stream: InputStream, maxBytes: Int, status: Int): ByteArray {
        val bytes = stream.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw invalidResponse(status)
        return bytes
    }
}

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
        "LIBRARY_TOO_LARGE" ->
            "Your library, with its highlights and review history, is too large to sync in one answer; the lists on this phone were not updated."
        "SIGN_IN_REQUIRED",
        "HTTP_401" -> "Sign in again to refresh from GitHub."
        "SESSION_UNREADABLE" -> "This phone couldn't read its sign-in just now. Nothing was sent; try again."
        "NOT_FOUND" -> "Not found."
        else -> message
    }
