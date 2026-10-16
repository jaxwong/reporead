package com.reporead.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.sync.Sync
import com.reporead.android.sync.backgroundSync
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sync's failure rules, the background sync's outcomes, and the lock it shares with sign-out, through production
 * Sync/Api/Room wiring against a test-only loopback HTTP server and an in-memory database; never the user's backend or
 * database.
 */
@RunWith(AndroidJUnit4::class)
class LocalSyncTest {
    private val sha = "a".repeat(40)
    private lateinit var store: LocalStore
    private lateinit var dao: LibraryDao
    private lateinit var directory: File

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        dao = store.library()
        directory = File(context.cacheDir, "test-only-local-sync-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After
    fun close() {
        store.close()
        check(directory.deleteRecursively())
    }

    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    /** The empty complete lists a sync ends with. */
    private val emptyLists =
        mapOf(
            "GET /api/repositories" to (200 to """{"repositories":[]}"""),
            "GET /api/reading-states" to (200 to """{"readingStates":[]}"""),
            "GET /api/bookmarks" to (200 to """{"bookmarks":[]}"""),
            "GET /api/notebook" to (200 to """{"annotations":[],"reviews":[],"sessionLimit":40}"""),
        )

    private fun reading(documentId: Long) =
        ReadingRow(
            documentId,
            sha,
            50,
            """{"headingPath":[],"textPrefix":null,"blockIndex":0}""",
            1_000,
            pending = true,
        )

    /** The server's reading state for document 1, as the reading-state PUT and the complete list return it. */
    private val serverReading =
        """{"documentId":1,"lastReadBlobSha":"$sha","progressPercent":50,"anchor":{"headingPath":[],"textPrefix":null,"blockIndex":0},
        "lastReadAt":"1970-01-01T00:00:01Z"}"""

    private fun sync(url: String) = Sync(Api(url, { "TEST_ONLY_SESSION" }) {}, store, directory) {}

    /** A port nothing listens on. */
    private fun closedPort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun aRefusedReadingPositionStaysOnThePhoneWithoutStoppingTheRestOfTheSync() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "n.md", "Long heading note", sha)))
        dao.saveReading(reading(1))
        dao.saveBookmark(BookmarkRow(2, bookmarked = true, pending = true, changedAt = 5))
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "PUT /api/documents/1/reading-state" to failure(400, "INVALID_READING_STATE"),
                        "PUT /api/documents/2/bookmark" to (200 to """{"documentId":2}"""),
                    )
            )
            .use { server ->
                val result = Sync(Api(server.url, { "TEST_ONLY_SESSION" }) {}, store, directory) {}.syncLocalChanges()
                assertEquals(1, result.refused.size)
                assertTrue(result.refused.single(), result.refused.single().contains("Long heading note"))
                assertNull(result.notSent)
                // The bookmark and the complete lists were still synced after the refusal.
                assertEquals(
                    listOf(
                        "PUT /api/documents/1/reading-state",
                        "PUT /api/documents/2/bookmark",
                        "GET /api/repositories",
                        "GET /api/reading-states",
                        "GET /api/bookmarks",
                        "GET /api/notebook",
                    ),
                    server.requests,
                )
                // The refused position is kept, pending, rather than replaced by the server's list that lacks it.
                assertTrue(dao.reading(1)!!.pending)
            }
    }

    @Test
    fun creationsWaitWhenGitHubAccessFailsWhileDatabaseOnlyWorkStillSyncs() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveAnnotation(
            AnnotationRow(
                "unsent-card",
                null,
                1,
                sha,
                "b1",
                0,
                4,
                "text",
                null,
                1,
                1,
                state = AnnotationState.UNSENT,
                type = AnnotationType.CARD,
                question = "Test-only question?",
                checkedBlobSha = sha,
            )
        )
        dao.saveReview(ReviewRow("grade-of-unsent-card", "unsent-card", 4, 2, sha, pending = true))
        dao.saveReading(reading(1))
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "POST /api/documents/1/annotations" to failure(403, "GITHUB_ACCESS_DENIED"),
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                    )
            )
            .use { server ->
                val result = Sync(Api(server.url, { "TEST_ONLY_SESSION" }) {}, store, directory) {}.syncLocalChanges()
                assertEquals("GITHUB_ACCESS_DENIED", result.notSent?.code)
                // One creation attempt ends the GitHub-backed step; the grade waits for its card; the reading position
                // is sent.
                assertEquals(
                    listOf(
                        "POST /api/documents/1/annotations",
                        "PUT /api/documents/1/reading-state",
                        "GET /api/repositories",
                        "GET /api/reading-states",
                        "GET /api/bookmarks",
                        "GET /api/notebook",
                    ),
                    server.requests,
                )
                assertEquals(setOf("unsent", "unsent-card"), dao.waitingAnnotations().map { it.mutationId }.toSet())
                assertNull(dao.annotation("unsent")!!.rejection)
                assertEquals(listOf("grade-of-unsent-card"), dao.pendingReviews().map { it.mutationId })
                assertTrue(dao.pendingReading().isEmpty())
            }
    }

    /**
     * Any failure of the creation step, including an answer that breaks the contract, waits like a GitHub failure does.
     */
    @Test
    fun aCreationAnswerThatBreaksTheContractWaitsWhileDatabaseOnlyWorkStillSyncs() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow("unparsed", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveReading(reading(1))
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "POST /api/documents/1/annotations" to (201 to """{"id":9}"""),
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                    )
            )
            .use { server ->
                val result = Sync(Api(server.url, { "TEST_ONLY_SESSION" }) {}, store, directory) {}.syncLocalChanges()
                assertEquals("INVALID_RESPONSE", result.notSent?.code)
                assertEquals(
                    listOf(
                        "POST /api/documents/1/annotations",
                        "PUT /api/documents/1/reading-state",
                        "GET /api/repositories",
                        "GET /api/reading-states",
                        "GET /api/bookmarks",
                        "GET /api/notebook",
                    ),
                    server.requests,
                )
                // Still pending under its mutation id: the next sync replays it, and the server answers with the same
                // highlight.
                assertEquals(listOf("unparsed"), dao.waitingAnnotations().map { it.mutationId })
                assertTrue(dao.pendingReading().isEmpty())
            }
    }

    @Test
    fun aDeletedCreationIsReplayedThenDeletedOnTheServer() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow("deleted", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        Sync(Api("http://127.0.0.1:9", { "TEST_ONLY_SESSION" }) {}, store, directory) {}
            .deleteAnnotation(dao.annotation("deleted")!!)
        assertTrue(dao.annotations(1).first().isEmpty())
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "POST /api/documents/1/annotations" to (200 to serverHighlight("deleted")),
                        "DELETE /api/annotations/9?expectedVersion=1" to (204 to ""),
                    )
            )
            .use { server ->
                val result = Sync(Api(server.url, { "TEST_ONLY_SESSION" }) {}, store, directory) {}.syncLocalChanges()
                assertNull(result.notSent)
                assertEquals(
                    listOf(
                        "POST /api/documents/1/annotations",
                        "DELETE /api/annotations/9?expectedVersion=1",
                        "GET /api/repositories",
                        "GET /api/reading-states",
                        "GET /api/bookmarks",
                        "GET /api/notebook",
                    ),
                    server.requests,
                )
                assertNull(dao.annotation("deleted"))
            }
    }

    /** The server's answer to creating highlight [mutationId] on document 1, as server id 9. */
    private fun serverHighlight(mutationId: String) =
        JSONObject()
            .put("id", 9)
            .put("mutationId", mutationId)
            .put("documentId", 1)
            .put("type", "HIGHLIGHT")
            .put("note", JSONObject.NULL)
            .put("status", "ANCHORED")
            .put("version", 1)
            .put("createdAt", "1970-01-01T00:00:00Z")
            .put("updatedAt", "1970-01-01T00:00:00Z")
            .put("anchor", passage())
            .put("location", passage())
            .put("resolvedBlobSha", sha)
            .put("question", JSONObject.NULL)
            .put("checkedBlobSha", JSONObject.NULL)
            .toString()

    private fun passage() =
        JSONObject()
            .put("sourceBlobSha", sha)
            .put("blockId", "b1")
            .put("exactText", "text")
            .put("prefixText", "")
            .put("suffixText", "")
            .put("startOffset", 0)
            .put("endOffset", 4)
            .put("headingPath", org.json.JSONArray())

    @Test
    fun anUnreachableServerEndsTheSyncWithEverythingPending() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveReading(reading(1))
        try {
            sync("http://127.0.0.1:${closedPort()}").syncLocalChanges()
            fail("Expected the unreachable server to end the sync")
        } catch (error: ApiException) {
            assertEquals("BACKEND_UNAVAILABLE", error.code)
        }
        assertEquals(1, dao.waitingAnnotations().size)
        assertEquals(1, dao.pendingReading().size)
    }

    @Test
    fun everyLocalChangeAsksForABackgroundSync() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "a", sha)))
        dao.saveNote(NoteRow(1, sha, "a.md", "a", "<html/>", 0, "text"))
        var requested = 0
        // Nothing listens on the port: saving a change never waits for the network.
        val sync =
            Sync(Api("http://127.0.0.1:${closedPort()}", { "TEST_ONLY_SESSION" }) {}, store, directory) { requested++ }
        sync.saveReading(reading(1))
        assertEquals(1, requested)
        sync.setBookmark(1, true)
        assertEquals(2, requested)
        val passage = Passage(sha, "b1", 0, 4, "text")
        val highlight = sync.createAnnotation(passage, 1, null)
        assertEquals(3, requested)
        sync.deleteAnnotation(dao.annotation(highlight)!!)
        assertEquals(4, requested)
        val card = sync.createAnnotation(passage, 1, null, "Test-only question?")
        assertTrue(sync.recordReview(ReviewRow("grade", card, 4, 1, sha, pending = true)))
        assertEquals(6, requested)
        sync.deleteAnnotation(dao.annotation(card)!!)
        assertEquals(7, requested)
        // A grade the card no longer accepts saves nothing, so there is nothing to send.
        assertFalse(sync.recordReview(ReviewRow("stale", card, 4, 2, sha, pending = true)))
        assertEquals(7, requested)
    }

    @Test
    fun signingOutWaitsForARunningSyncSoTheServersListsCannotComeBack() = runBlocking {
        dao.saveReading(reading(1))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                        "GET /api/reading-states" to (200 to """{"readingStates":[$serverReading]}"""),
                    )
            ) { request ->
                if (request == "PUT /api/documents/1/reading-state") {
                    arrived.countDown()
                    check(release.await(10, SECONDS)) { "Test never released the held request" }
                }
            }
            .use { server ->
                val sync = sync(server.url)
                val syncing = async(Dispatchers.IO) { sync.syncLocalChanges() }
                assertTrue(arrived.await(10, SECONDS))
                val clearing = async(Dispatchers.IO) { sync.clearAll() }
                delay(500)
                assertFalse("Clearing the phone must wait for the running sync", clearing.isCompleted)
                release.countDown()
                syncing.await()
                clearing.await()
                // The sync wrote the server's reading state, then sign-out cleared it.
                assertNull(dao.reading(1))
                assertEquals(Result.success(), backgroundSync(sync, 0))
                assertEquals(5, server.requests.size)
            }
    }

    /**
     * The reader sends a new highlight at once; its answer must not be written back into a phone sign-out has cleared.
     */
    @Test
    fun signingOutWaitsForAHighlightBeingSentSoItCannotComeBack() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow("sent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("POST /api/documents/1/annotations" to (201 to serverHighlight("sent")))) {
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the held request" }
            }
            .use { server ->
                val sync = sync(server.url)
                val sending = async(Dispatchers.IO) { sync.pushAnnotations() }
                assertTrue(arrived.await(10, SECONDS))
                val clearing = async(Dispatchers.IO) { sync.clearAll() }
                try {
                    delay(500)
                    assertFalse("Clearing the phone must wait for the highlight being sent", clearing.isCompleted)
                } finally {
                    // A failed check must still free the server thread, or its timeout crashes the run and hides the
                    // failure.
                    release.countDown()
                }
                sending.await()
                clearing.await()
                assertNull(dao.annotation("sent"))
            }
    }

    @Test
    fun theBackgroundSyncMakesNoRequestWhenNothingWaits() = runBlocking {
        // A refused highlight is not waiting: sending it again cannot deliver it.
        dao.saveAnnotation(
            AnnotationRow(
                "refused",
                null,
                1,
                sha,
                "b1",
                0,
                4,
                "text",
                null,
                1,
                0,
                state = AnnotationState.REFUSED,
                rejection = "Refused",
            )
        )
        LoopbackServer(emptyMap()).use { server ->
            assertEquals(Result.success(), backgroundSync(sync(server.url), 0))
            assertEquals(emptyList<String>(), server.requests)
        }
    }

    @Test
    fun theBackgroundSyncSendsWhatWaits() = runBlocking {
        dao.saveReading(reading(1))
        LoopbackServer(emptyLists + mapOf("PUT /api/documents/1/reading-state" to (200 to serverReading))).use { server
            ->
            assertEquals(Result.success(), backgroundSync(sync(server.url), 0))
            assertEquals(
                listOf(
                    "PUT /api/documents/1/reading-state",
                    "GET /api/repositories",
                    "GET /api/reading-states",
                    "GET /api/bookmarks",
                    "GET /api/notebook",
                ),
                server.requests,
            )
            assertTrue(dao.pendingReading().isEmpty())
        }
    }

    @Test
    fun anUnreachableServerIsTriedAgainUpToTenAttempts() = runBlocking {
        dao.saveReading(reading(1))
        val sync = sync("http://127.0.0.1:${closedPort()}")
        assertEquals(Result.retry(), backgroundSync(sync, 0))
        assertEquals(Result.retry(), backgroundSync(sync, 8))
        assertEquals(Result.failure(), backgroundSync(sync, 9))
        assertEquals(1, dao.pendingReading().size)
    }

    @Test
    fun gitHubUnavailableIsTriedAgainAfterTheRestIsSent() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveReading(reading(1))
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "POST /api/documents/1/annotations" to failure(503, "GITHUB_UNAVAILABLE"),
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                    )
            )
            .use { server ->
                assertEquals(Result.retry(), backgroundSync(sync(server.url), 0))
                assertTrue(dao.pendingReading().isEmpty())
                assertEquals(1, dao.waitingAnnotations().size)
            }
    }

    @Test
    fun signedOutOrAccessDeniedStopsUntilTheNextChange() = runBlocking {
        dao.saveReading(reading(1))
        LoopbackServer(mapOf("PUT /api/documents/1/reading-state" to failure(401, "SIGN_IN_REQUIRED"))).use { server ->
            assertEquals(Result.failure(), backgroundSync(sync(server.url), 0))
            assertEquals(1, dao.pendingReading().size)
        }
        dao.saveAnnotation(
            AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "POST /api/documents/1/annotations" to failure(403, "GITHUB_ACCESS_DENIED"),
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                    )
            )
            .use { server ->
                assertEquals(Result.failure(), backgroundSync(sync(server.url), 0))
                assertEquals(1, dao.waitingAnnotations().size)
            }
    }

    @Test
    fun aSyncLoadsEveryRepositorysNotesSoARemovedNoteWithKeptDataIsNamed() = runBlocking {
        val notes =
            """{"repositoryId":7,"lastSyncedCommitSha":"$sha","documents":[
            {"id":1,"path":"kept.md","title":"kept","blobSha":"$sha","contentChangedAt":null,"deleted":false},
            {"id":2,"path":"gone.md","title":"gone","blobSha":"$sha","contentChangedAt":null,"deleted":true}]}"""
        val readingOfTheRemovedNote = serverReading.replace("\"documentId\":1", "\"documentId\":2")
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "GET /api/repositories" to
                            (200 to
                                """{"repositories":[{"id":7,"fullName":"test-only/notes","documentCount":1,"lastSyncedCommitSha":"$sha"}]}"""),
                        "GET /api/repositories/7/documents" to (200 to notes),
                        "GET /api/reading-states" to (200 to """{"readingStates":[$readingOfTheRemovedNote]}"""),
                    )
            )
            .use { server ->
                sync(server.url).syncLocalChanges()
                assertEquals(
                    listOf(
                        "GET /api/repositories",
                        "GET /api/repositories/7/documents",
                        "GET /api/reading-states",
                        "GET /api/bookmarks",
                        "GET /api/notebook",
                    ),
                    server.requests,
                )
                assertEquals(listOf(1L), dao.documents(7).first().map { it.id })
                assertEquals(
                    listOf(ReadNote(2, "gone", "gone.md", deleted = true, progressPercent = 50)),
                    dao.recentReading(5).first(),
                )
            }
    }

    @Test
    fun aRepositoryTheServerAlreadyDisconnectedIsRemovedFromThePhoneInsteadOfFailing() = runBlocking {
        dao.replaceRepositories(listOf(RepositoryRow(7, "o/notes", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "a", sha)))
        dao.saveNote(NoteRow(1, sha, "a.md", "a", "<html/>", 0, "private text"))
        LoopbackServer(
                emptyLists +
                    mapOf(
                        "GET /api/repositories/7/stored-data" to failure(404, "NOT_FOUND"),
                        "GET /api/repositories" to (200 to """{"repositories":[]}"""),
                    )
            )
            .use { server ->
                assertNull(Sync(Api(server.url, { "TEST_ONLY_SESSION" }) {}, store, directory) {}.disconnectPreview(7))
                assertTrue(dao.repositoryIds().isEmpty())
                assertNull(dao.note(1))
            }
    }
}

/**
 * Test-only loopback HTTP server: answers each "METHOD /path" from [routes] (status and JSON body) and records every
 * request line. An unknown route is answered 500 and recorded, so it fails the test visibly. [beforeAnswer] runs on the
 * server thread after recording a request, so a test can hold one open.
 */
internal class LoopbackServer(
    private val routes: Map<String, Pair<Int, String>>,
    private val beforeAnswer: (String) -> Unit = {},
) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val worker =
        thread(isDaemon = true, name = "test-only-loopback") {
            while (true) {
                val client =
                    try {
                        socket.accept()
                    } catch (closed: SocketException) {
                        break
                    }
                client.use(::serve)
            }
        }

    private fun serve(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
        val line = input.readLine() ?: return
        var length = 0
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true))
                length = header.substringAfter(':').trim().toInt()
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val added = input.read(body, read, length - read)
            check(added > 0) { "Incomplete test HTTP body" }
            read += added
        }
        val key = line.substringBeforeLast(" HTTP/")
        requests += key
        beforeAnswer(key)
        val (status, json) = routes[key] ?: (500 to """{"code":"TEST_UNEXPECTED_ROUTE","message":"$key"}""")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        output.write(
            "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        socket.close()
        worker.join(5_000)
    }
}
