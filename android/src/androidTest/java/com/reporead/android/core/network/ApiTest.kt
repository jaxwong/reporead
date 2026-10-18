package com.reporead.android.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.auth.StoredSession
import com.reporead.android.data.AnnotationType
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
    private val sha = "a".repeat(40)
    private val commit = "b".repeat(40)
    private val at = Instant.parse("2000-01-01T00:00:00Z")

    private fun fixture(name: String) =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("api-contract/$name.json")
            .bufferedReader()
            .use { it.readText() }

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
        val body = fixture("library-empty")
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
                "POST /api/app-auth/token" to (200 to fixture("session")),
                "DELETE /api/app-auth/session" to (204 to ""),
                "DELETE /api/account" to (200 to fixture("account-deleted")),
                "GET /api/library" to (200 to fixture("library")),
                "GET /api/repositories/available" to (200 to fixture("available")),
                "POST /api/repositories/11/connect" to (200 to fixture("connection")),
                "POST /api/repositories/7/sync" to (200 to fixture("synced")),
                "GET /api/repositories/7/stored-data" to (200 to fixture("stored-data")),
                "DELETE /api/repositories/7" to (200 to fixture("disconnected")),
                "GET /api/documents/1/content" to (200 to fixture("content")),
                "GET /api/documents/1/image?commitSha=$commit&path=img%2Fa+b.png" to (200 to "TEST_ONLY_BYTES"),
                "GET /api/documents/1/changes?since=$sha&to=$commit" to (200 to fixture("changes")),
                "PUT /api/documents/1/reading-state" to (200 to fixture("reading")),
                "PUT /api/documents/1/bookmark" to (200 to fixture("bookmark")),
                "DELETE /api/documents/1/bookmark" to (204 to ""),
                "GET /api/documents/1/annotations" to (200 to fixture("annotations")),
                "POST /api/documents/1/annotations" to (201 to fixture("annotation")),
                "PATCH /api/annotations/9" to (200 to fixture("annotation")),
                "DELETE /api/annotations/9?expectedVersion=1" to (204 to ""),
                "POST /api/annotations/9/reattach" to (200 to fixture("annotation")),
                "POST /api/annotations/9/check" to (200 to fixture("annotation")),
                "POST /api/annotations/10/reviews" to (200 to fixture("review")),
            )
        LoopbackServer(routes).use { server ->
            val api = Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) { fail("No 401 expected") }
            val selection = SelectionRequest(sha, "b1", 0, 4, "text")
            val create =
                CreateAnnotationRequest(
                    "00000000-0000-0000-0000-000000000001",
                    selection,
                    null,
                    AnnotationType.HIGHLIGHT,
                    null,
                )
            api.exchangeToken(TokenRequest("TEST_ONLY_CODE", "TEST_ONLY_VERIFIER", null))
            api.revokeSession()
            api.deleteAccount()
            api.library()
            api.availableRepositories()
            api.connectRepository(11, ConnectRequest(7))
            api.syncRepository(7)
            api.storedData(7)
            api.disconnectRepository(7)
            api.noteContent(1)
            assertEquals("TEST_ONLY_BYTES", api.noteImage(1, commit, "img/a b.png").toString(Charsets.UTF_8))
            api.changes(1, sha, commit)
            api.saveReading(1, ReadingRequest(sha, 50, ReadingAnchor(emptyList(), null, 0), at))
            api.setBookmark(1)
            api.clearBookmark(1)
            api.annotations(1)
            api.createAnnotation(1, create)
            api.editAnnotation(9, EditAnnotationRequest(null, 1))
            api.deleteAnnotation(9, 1)
            api.reattachAnnotation(9, ReattachRequest(1, selection))
            api.checkCard(9, CheckRequest(1, sha))
            api.recordReview(10, ReviewRequest("00000000-0000-0000-0000-000000000002", 4, at, sha))
            assertEquals(routes.keys.toList(), server.requests)
        }
        LoopbackServer(mapOf("POST /api/documents/1/annotations" to (200 to fixture("annotation")))).use { server ->
            Api(server.url, { StoredSession.Absent }) {}
                .createAnnotation(
                    1,
                    CreateAnnotationRequest(
                        "00000000-0000-0000-0000-000000000001",
                        SelectionRequest(sha, "b1", 0, 4, "text"),
                        null,
                        AnnotationType.HIGHLIGHT,
                        null,
                    ),
                )
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun unexpectedSuccessStatusesAndUnrelatedAnswersNeverCountAsConfirmation() = runBlocking {
        for ((status, body) in
            listOf(
                204 to "",
                201 to fixture("content"),
                200 to fixture("content").replace("\"documentId\": 1", "\"documentId\": 2"),
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
        LoopbackServer(mapOf("PUT /api/documents/1/reading-state" to (200 to fixture("reading")))).use { server ->
            val newer =
                Api(server.url, { StoredSession.Absent }) {}
                    .saveReading(
                        1,
                        ReadingRequest(sha, 0, ReadingAnchor(emptyList(), null, 0), Instant.EPOCH),
                    )
            assertEquals(at, newer.lastReadAt)
            assertEquals(50, newer.progressPercent)
        }
    }

    @Test
    fun wellTypedAnswersStillHaveToBelongToTheirRequest() = runBlocking {
        val wrongAnnotation = JSONObject(fixture("annotation")).put("id", 8).toString()
        val cases: List<Triple<String, String, suspend (Api) -> Any>> =
            listOf(
                Triple(
                    "POST /api/repositories/11/connect",
                    JSONObject(fixture("connection")).put("githubRepositoryId", 12).toString(),
                    { it.connectRepository(11, ConnectRequest(7)) },
                ),
                Triple(
                    "POST /api/repositories/7/sync",
                    JSONObject(fixture("synced")).put("repositoryId", 8).toString(),
                    { it.syncRepository(7) },
                ),
                Triple(
                    "GET /api/documents/1/annotations",
                    fixture("annotations").replace("\"documentId\": 1", "\"documentId\": 2"),
                    { it.annotations(1) },
                ),
                Triple(
                    "POST /api/documents/1/annotations",
                    JSONObject(fixture("annotation")).put("mutationId", "test-only-other-mutation").toString(),
                    {
                        it.createAnnotation(
                            1,
                            CreateAnnotationRequest(
                                "00000000-0000-0000-0000-000000000001",
                                SelectionRequest(sha, "b1", 0, 4, "text"),
                                null,
                                AnnotationType.HIGHLIGHT,
                                null,
                            ),
                        )
                    },
                ),
                Triple(
                    "PATCH /api/annotations/9",
                    wrongAnnotation,
                    { it.editAnnotation(9, EditAnnotationRequest(null, 1)) },
                ),
                Triple(
                    "POST /api/annotations/9/reattach",
                    wrongAnnotation,
                    { it.reattachAnnotation(9, ReattachRequest(1, SelectionRequest(sha, "b1", 0, 4, "text"))) },
                ),
                Triple("POST /api/annotations/9/check", wrongAnnotation, { it.checkCard(9, CheckRequest(1, sha)) }),
                Triple(
                    "GET /api/documents/1/changes?since=$sha&to=$commit",
                    fixture("changes").replace(sha, "c".repeat(40)),
                    { it.changes(1, sha, commit) },
                ),
                Triple(
                    "POST /api/annotations/10/reviews",
                    JSONObject(fixture("review")).put("grade", 3).toString(),
                    { it.recordReview(10, ReviewRequest("00000000-0000-0000-0000-000000000002", 4, at, sha)) },
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
