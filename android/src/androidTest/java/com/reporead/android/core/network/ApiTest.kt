// Owns: Device HTTP-boundary coverage using loopback responses, including actual framing and read timeouts.

package com.reporead.android.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.wire.AnnotationControllerAnnotationList
import com.reporead.android.core.network.wire.AnnotationControllerCheckRequest
import com.reporead.android.core.network.wire.AnnotationControllerCreateRequest
import com.reporead.android.core.network.wire.AnnotationControllerEditRequest
import com.reporead.android.core.network.wire.AnnotationControllerReattachRequest
import com.reporead.android.core.network.wire.AnnotationServiceSelection
import com.reporead.android.core.network.wire.AppAuthControllerTokenRequest
import com.reporead.android.core.network.wire.ReadingControllerAnchorRequest
import com.reporead.android.core.network.wire.ReadingControllerSaveRequest
import com.reporead.android.core.network.wire.RepositoryControllerAvailableList
import com.reporead.android.core.network.wire.RepositoryControllerConnectRequest
import com.reporead.android.core.network.wire.ReviewControllerGradeRequest
import com.reporead.android.data.LoopbackServer
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Api owns what a 401 means: the session it sent is no longer accepted. Test-only loopback server, never the backend.
 */
@RunWith(AndroidJUnit4::class)
class ApiTest {
    private val selection =
        AnnotationServiceSelection(
            blockId = "b1",
            endOffset = 4,
            exactText = "text",
            sourceBlobSha = testSha,
            startOffset = 0,
        )
    private val create =
        AnnotationControllerCreateRequest(
            anchor = selection,
            mutationId = TEST_HIGHLIGHT_MUTATION,
            note = null,
            question = null,
            type = "HIGHLIGHT",
        )
    private val edit = AnnotationControllerEditRequest(expectedVersion = 1, note = null)
    private val reattach = AnnotationControllerReattachRequest(anchor = selection, expectedVersion = 1)
    private val check = AnnotationControllerCheckRequest(blobSha = testSha, expectedVersion = 1)
    private val grade =
        ReviewControllerGradeRequest(
            blobSha = testSha,
            grade = 4,
            mutationId = TEST_REVIEW_MUTATION,
            reviewedAt = testAt,
        )
    private val connect = RepositoryControllerConnectRequest(installationId = 7)

    private fun reading(progressPercent: Int, lastReadAt: Instant) =
        ReadingControllerSaveRequest(
            anchor = ReadingControllerAnchorRequest(blockIndex = 0, headingPath = emptyList(), textPrefix = null),
            lastReadAt = lastReadAt,
            lastReadBlobSha = testSha,
            progressPercent = progressPercent,
        )

    private suspend fun rawAnswer(body: String, declaredBytes: Int, holdOpen: Boolean): ApiException = coroutineScope {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val writer =
                async(Dispatchers.IO) {
                    server.accept().use { client ->
                        client.soTimeout = 5_000
                        val input = client.getInputStream().bufferedReader()
                        assertEquals("GET /api/library HTTP/1.1", input.readLine())
                        while (checkNotNull(input.readLine()).isNotEmpty()) {
                            /* consume the request headers */
                        }
                        val output = client.getOutputStream()
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $declaredBytes\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        output.write(body.toByteArray(Charsets.UTF_8))
                        output.flush()
                        if (holdOpen) delay(46_000) // The production read deadline is 45 s, not a test-selected cap.
                    }
                }
            val error = runCatching {
                Api("http://127.0.0.1:${server.localPort}", { StoredSession.Absent }) {}.library()
            }
                .exceptionOrNull()
            writer.await()
            assertTrue("An incomplete HTTP answer must fail, got $error", error is ApiException)
            error as ApiException
        }
    }

    @Test
    fun validJsonInATruncatedHttpBodyIsNotACompleteAnswer() = runBlocking {
        val body = wire(testLibrary())
        val error = rawAnswer(body, body.toByteArray(Charsets.UTF_8).size + 1, false)
        // HttpURLConnection rejects truncated framing as I/O, before the valid JSON can be published.
        assertEquals("BACKEND_UNAVAILABLE", error.code)
        assertEquals(0, error.status)
        assertTrue(error.cause is IOException)
    }

    @Test
    fun aStalledResponseUsesTheExistingReadDeadlineAndKeepsItsIoCause() = runBlocking {
        val error = rawAnswer("", 100, true)
        assertEquals("BACKEND_UNAVAILABLE", error.code)
        assertTrue(error.cause is SocketTimeoutException)
    }

    @Test
    fun everyPublicRouteUsesItsDeclaredMethodPathStatusAndResponse() = runBlocking {
        val routes =
            linkedMapOf(
                "POST /api/app-auth/token" to (200 to wire(testSession())),
                "DELETE /api/app-auth/session" to (204 to ""),
                "DELETE /api/account" to (200 to wire(testAccountDeleted())),
                "GET /api/library" to (200 to wire(testLibrary())),
                "GET /api/repositories/available" to
                    (200 to wire(RepositoryControllerAvailableList(repositories = listOf(testAvailable())))),
                "POST /api/repositories/11/connect" to (200 to wire(testConnection())),
                "POST /api/repositories/7/sync" to (200 to wire(testSynced())),
                "GET /api/repositories/7/stored-data" to (200 to wire(testCounts())),
                "DELETE /api/repositories/7" to (200 to wire(testDisconnected())),
                "GET /api/documents/1/content" to (200 to wire(testContent())),
                "GET /api/documents/1/image?commitSha=$testCommit&path=img%2Fa+b.png" to (200 to "TEST_ONLY_BYTES"),
                "PUT /api/documents/1/reading-state" to (200 to wire(testReading())),
                "PUT /api/documents/1/bookmark" to (200 to wire(testBookmark())),
                "DELETE /api/documents/1/bookmark" to (204 to ""),
                "GET /api/documents/1/annotations" to
                    (200 to wire(AnnotationControllerAnnotationList(annotations = listOf(testHighlight())))),
                "POST /api/documents/1/annotations" to (201 to wire(testHighlight())),
                "PATCH /api/annotations/9" to (200 to wire(testHighlight())),
                "DELETE /api/annotations/9?expectedVersion=1" to (204 to ""),
                "POST /api/annotations/9/reattach" to (200 to wire(testHighlight())),
                "POST /api/annotations/9/check" to (200 to wire(testHighlight())),
                "POST /api/annotations/10/reviews" to (200 to wire(testReview())),
            )
        LoopbackServer(routes).use { server ->
            val api = Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) { fail("No 401 expected") }
            api.exchangeToken(
                AppAuthControllerTokenRequest(
                    code = "TEST_ONLY_CODE",
                    codeVerifier = "TEST_ONLY_VERIFIER",
                )
            )
            api.revokeSession()
            api.deleteAccount()
            api.library()
            api.availableRepositories()
            api.connectRepository(11, connect)
            api.syncRepository(7)
            api.storedData(7)
            api.disconnectRepository(7)
            api.noteContent(1)
            assertEquals("TEST_ONLY_BYTES", api.noteImage(1, testCommit, "img/a b.png").toString(Charsets.UTF_8))
            api.saveReading(1, reading(50, testAt))
            api.setBookmark(1)
            api.clearBookmark(1)
            api.annotations(1)
            api.createAnnotation(1, create)
            api.editAnnotation(9, edit)
            api.deleteAnnotation(9, 1)
            api.reattachAnnotation(9, reattach)
            api.checkCard(9, check)
            api.recordReview(10, grade)
            assertEquals(routes.keys.toList(), server.requests)
        }
        LoopbackServer(mapOf("POST /api/documents/1/annotations" to (200 to wire(testHighlight())))).use { server ->
            Api(server.url, { StoredSession.Absent }) {}.createAnnotation(1, create)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun unexpectedSuccessStatusesAndUnrelatedAnswersNeverCountAsConfirmation() = runBlocking {
        for ((status, body) in
            listOf(
                204 to "",
                201 to wire(testContent()),
                200 to wire(testContent().copy(documentId = 2)),
            )) {
            LoopbackServer(mapOf("GET /api/documents/1/content" to (status to body))).use { server ->
                val error =
                    runCatching { Api(server.url, { StoredSession.Absent }) {}.noteContent(1) }.exceptionOrNull()
                        as ApiException
                assertEquals("INVALID_RESPONSE", error.code)
                assertEquals(status, error.status)
                assertEquals(1, server.requests.size)
            }
        }
        for (status in listOf(200, 201, 202)) {
            LoopbackServer(mapOf("DELETE /api/app-auth/session" to (status to "{}"))).use { server ->
                val error =
                    runCatching { Api(server.url, { StoredSession.Absent }) {}.revokeSession() }.exceptionOrNull()
                        as ApiException
                assertEquals("INVALID_RESPONSE", error.code)
                assertEquals(status, error.status)
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test
    fun aNewerReadingStateForTheRightDocumentIsAValidLastWriteWinsAnswer() = runBlocking {
        LoopbackServer(mapOf("PUT /api/documents/1/reading-state" to (200 to wire(testReading())))).use { server ->
            val newer = Api(server.url, { StoredSession.Absent }) {}.saveReading(1, reading(0, Instant.EPOCH))
            assertEquals(testAt, newer.lastReadAt)
            assertEquals(50, newer.progressPercent)
        }
    }

    @Test
    fun aReviewAcknowledgementMayCarryTheServersClampedTimestamp() = runBlocking {
        val clamped = testReview().copy(reviewedAt = Instant.EPOCH)
        LoopbackServer(mapOf("POST /api/annotations/10/reviews" to (200 to wire(clamped)))).use { server ->
            val stored =
                Api(server.url, { StoredSession.Absent }) {}
                    .recordReview(10, grade.copy(reviewedAt = Instant.EPOCH.minusMillis(1)))
            assertEquals(Instant.EPOCH, stored.reviewedAt)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun wellTypedAnswersStillHaveToBelongToTheirRequest() = runBlocking {
        val wrongAnnotation = wire(testHighlight().copy(id = 8))
        val cases: List<Triple<String, String, suspend (Api) -> Any>> =
            listOf(
                Triple(
                    "POST /api/repositories/11/connect",
                    wire(testConnection().copy(githubRepositoryId = 12)),
                    { it.connectRepository(11, connect) },
                ),
                Triple(
                    "POST /api/repositories/7/sync",
                    wire(testSynced().copy(repositoryId = 8)),
                    { it.syncRepository(7) },
                ),
                Triple(
                    "GET /api/documents/1/annotations",
                    wire(
                        AnnotationControllerAnnotationList(annotations = listOf(testHighlight().copy(documentId = 2)))
                    ),
                    { it.annotations(1) },
                ),
                Triple(
                    "POST /api/documents/1/annotations",
                    wire(testHighlight().copy(mutationId = "test-only-other-mutation")),
                    { it.createAnnotation(1, create) },
                ),
                Triple(
                    "PATCH /api/annotations/9",
                    wrongAnnotation,
                    { it.editAnnotation(9, edit) },
                ),
                Triple(
                    "POST /api/annotations/9/reattach",
                    wrongAnnotation,
                    { it.reattachAnnotation(9, reattach) },
                ),
                Triple("POST /api/annotations/9/check", wrongAnnotation, { it.checkCard(9, check) }),
                Triple(
                    "POST /api/annotations/10/reviews",
                    wire(testReview().copy(grade = 3)),
                    { it.recordReview(10, grade) },
                ),
            )
        for ((route, body, call) in cases) {
            LoopbackServer(mapOf(route to (200 to body))).use { server ->
                val error =
                    runCatching { call(Api(server.url, { StoredSession.Absent }) {}) }.exceptionOrNull() as ApiException
                assertEquals(route, "INVALID_RESPONSE", error.code)
                assertEquals(200, error.status)
                assertEquals(listOf(route), server.requests)
            }
        }
    }

    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    @Test
    fun aRefusedSessionIsReportedOnceWithTheTokenThatWasSent() = runBlocking {
        val refused = mutableListOf<String?>()
        LoopbackServer(
                mapOf(
                    "GET /api/documents/1/annotations" to failure(401, "HTTP_401"),
                    "GET /api/library" to failure(401, "SIGN_IN_REQUIRED"),
                    "GET /api/repositories/available" to failure(503, "GITHUB_UNAVAILABLE"),
                    "GET /api/documents/1/content" to failure(404, "NOT_FOUND"),
                )
            )
            .use { server ->
                val api = Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) { refused += it }
                for (call in
                    listOf<suspend () -> Any>(
                        { api.annotations(1) },
                        { api.library() },
                        { api.availableRepositories() },
                        { api.noteContent(1) },
                    )) {
                    try {
                        call()
                        fail("Each test-only route must fail")
                    } catch (expected: ApiException) {
                        assertTrue(expected.status != 0)
                    }
                }
            }
        // Only the two 401s, each naming the session it was sent with; other failures say nothing about the session.
        assertEquals(listOf("TEST_ONLY_SESSION", "TEST_ONLY_SESSION"), refused)
    }
}
