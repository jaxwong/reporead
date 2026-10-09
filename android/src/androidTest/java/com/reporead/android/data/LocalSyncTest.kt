package com.reporead.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.sync.Sync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The explicit sync's failure rules, through production Sync/Api/Room wiring against a test-only loopback HTTP server
 * and an in-memory database; never the user's backend or database.
 */
@RunWith(AndroidJUnit4::class)
class LocalSyncTest {
    private val sha = "a".repeat(40)
    private lateinit var store: LocalStore
    private lateinit var dao: LibraryDao
    private lateinit var directory: File

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        dao = store.library()
        directory = File(context.cacheDir, "test-only-local-sync-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After fun close() {
        store.close()
        check(directory.deleteRecursively())
    }

    private fun failure(status: Int, code: String) = status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    /** The empty complete lists a sync ends with. */
    private val emptyLists = mapOf(
        "GET /api/reading-states" to (200 to """{"readingStates":[]}"""),
        "GET /api/bookmarks" to (200 to """{"bookmarks":[]}"""),
        "GET /api/notebook" to (200 to """{"annotations":[],"reviews":[],"sessionLimit":40}"""),
    )

    private fun reading(documentId: Long, title: String) = ReadingRow(documentId, title, "n.md", sha, 50,
        """{"headingPath":[],"textPrefix":null,"blockIndex":0}""", 1_000, pending = true)

    @Test fun aRefusedReadingPositionStaysOnThePhoneWithoutStoppingTheRestOfTheSync() = runBlocking {
        dao.saveReading(reading(1, "Long heading note"))
        dao.saveBookmark(BookmarkRow(2, "Kept", "k.md", sha, bookmarked = true, pending = true, changedAt = 5))
        LoopbackServer(emptyLists + mapOf(
            "PUT /api/documents/1/reading-state" to failure(400, "INVALID_READING_STATE"),
            "PUT /api/documents/2/bookmark" to (200 to """{"documentId":2}"""),
        )).use { server ->
            val result = Sync(Api(server.url) { "TEST_ONLY_SESSION" }, store, directory).syncLocalChanges()
            assertEquals(1, result.refused.size)
            assertTrue(result.refused.single(), result.refused.single().contains("Long heading note"))
            assertNull(result.notSent)
            // The bookmark and the complete lists were still synced after the refusal.
            assertEquals(listOf("PUT /api/documents/1/reading-state", "PUT /api/documents/2/bookmark", "GET /api/reading-states",
                "GET /api/bookmarks", "GET /api/notebook"), server.requests)
            // The refused position is kept, pending, rather than replaced by the server's list that lacks it.
            assertTrue(dao.reading(1)!!.pending)
        }
    }

    @Test fun creationsWaitWhenGitHubAccessFailsWhileDatabaseOnlyWorkStillSyncs() = runBlocking {
        dao.saveAnnotation(AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, pending = true, rejection = null))
        dao.saveAnnotation(AnnotationRow("unsent-card", null, 1, sha, "b1", 0, 4, "text", null, 1, 1, pending = true, rejection = null,
            type = "CARD", question = "Test-only question?", checkedBlobSha = sha))
        dao.saveReview(ReviewRow("grade-of-unsent-card", "unsent-card", 4, 2, sha, pending = true))
        dao.saveReading(reading(1, "Note"))
        LoopbackServer(emptyLists + mapOf(
            "POST /api/documents/1/annotations" to failure(403, "GITHUB_ACCESS_DENIED"),
            "PUT /api/documents/1/reading-state" to (200 to """{"documentId":1,"repositoryId":7,"path":"n.md","title":"Note","currentBlobSha":"$sha",
                "deleted":false,"lastReadBlobSha":"$sha","progressPercent":50,"anchor":{"headingPath":[],"textPrefix":null,"blockIndex":0},
                "lastReadAt":"1970-01-01T00:00:01Z"}"""),
        )).use { server ->
            val result = Sync(Api(server.url) { "TEST_ONLY_SESSION" }, store, directory).syncLocalChanges()
            assertEquals("GITHUB_ACCESS_DENIED", result.notSent?.code)
            // One creation attempt ends the GitHub-backed step; the grade waits for its card; the reading position is sent.
            assertEquals(listOf("POST /api/documents/1/annotations", "PUT /api/documents/1/reading-state", "GET /api/reading-states",
                "GET /api/bookmarks", "GET /api/notebook"), server.requests)
            assertEquals(setOf("unsent", "unsent-card"), dao.pendingAnnotations().map { it.mutationId }.toSet())
            assertNull(dao.annotation("unsent")!!.rejection)
            assertEquals(listOf("grade-of-unsent-card"), dao.pendingReviews().map { it.mutationId })
            assertTrue(dao.pendingReading().isEmpty())
        }
    }

    @Test fun aDeletedCreationIsReplayedThenDeletedOnTheServer() = runBlocking {
        dao.saveAnnotation(AnnotationRow("deleted", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, pending = true, rejection = null))
        Sync(Api("http://127.0.0.1:9") { "TEST_ONLY_SESSION" }, store, directory).deleteAnnotation(dao.annotation("deleted")!!)
        assertTrue(dao.annotations(1).first().isEmpty())
        val created = JSONObject().put("id", 9).put("mutationId", "deleted").put("documentId", 1).put("type", "HIGHLIGHT").put("note", JSONObject.NULL)
            .put("status", "ANCHORED").put("version", 1).put("createdAt", "1970-01-01T00:00:00Z").put("updatedAt", "1970-01-01T00:00:00Z")
            .put("anchor", passage()).put("location", passage()).put("resolvedBlobSha", sha).put("question", JSONObject.NULL)
            .put("checkedBlobSha", JSONObject.NULL).put("title", "n").put("path", "n.md").put("currentBlobSha", sha).put("deleted", false)
        LoopbackServer(emptyLists + mapOf(
            "POST /api/documents/1/annotations" to (200 to created.toString()),
            "DELETE /api/annotations/9?expectedVersion=1" to (204 to ""),
        )).use { server ->
            val result = Sync(Api(server.url) { "TEST_ONLY_SESSION" }, store, directory).syncLocalChanges()
            assertNull(result.notSent)
            assertEquals(listOf("POST /api/documents/1/annotations", "DELETE /api/annotations/9?expectedVersion=1", "GET /api/reading-states",
                "GET /api/bookmarks", "GET /api/notebook"), server.requests)
            assertNull(dao.annotation("deleted"))
        }
    }

    private fun passage() = JSONObject().put("sourceBlobSha", sha).put("blockId", "b1").put("exactText", "text").put("prefixText", "")
        .put("suffixText", "").put("startOffset", 0).put("endOffset", 4).put("headingPath", org.json.JSONArray())

    @Test fun anUnreachableServerEndsTheSyncWithEverythingPending() = runBlocking {
        dao.saveAnnotation(AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, pending = true, rejection = null))
        dao.saveReading(reading(1, "Note"))
        // Nothing listens on a closed port.
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        try {
            Sync(Api("http://127.0.0.1:$port") { "TEST_ONLY_SESSION" }, store, directory).syncLocalChanges()
            fail("Expected the unreachable server to end the sync")
        } catch (error: ApiException) {
            assertEquals("BACKEND_UNAVAILABLE", error.code)
        }
        assertEquals(1, dao.pendingAnnotations().size)
        assertEquals(1, dao.pendingReading().size)
    }

    @Test fun aRepositoryTheServerAlreadyDisconnectedIsRemovedFromThePhoneInsteadOfFailing() = runBlocking {
        dao.replaceRepositories(listOf(RepositoryRow(7, "o/notes", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "a", sha)))
        dao.saveNote(NoteRow(1, sha, sha, "a.md", "a", "<html/>", 0, "private text"))
        LoopbackServer(emptyLists + mapOf(
            "GET /api/repositories/7/stored-data" to failure(404, "NOT_FOUND"),
            "GET /api/repositories" to (200 to """{"repositories":[]}"""),
        )).use { server ->
            assertNull(Sync(Api(server.url) { "TEST_ONLY_SESSION" }, store, directory).disconnectPreview(7))
            assertTrue(dao.repositoryIds().isEmpty())
            assertNull(dao.note(1))
        }
    }
}

/**
 * Test-only loopback HTTP server: answers each "METHOD /path" from [routes] (status and JSON body) and records every
 * request line. An unknown route is answered 500 and recorded, so it fails the test visibly.
 */
internal class LoopbackServer(private val routes: Map<String, Pair<Int, String>>) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val worker = thread(isDaemon = true, name = "test-only-loopback") {
        while (true) {
            val client = try { socket.accept() } catch (closed: SocketException) { break }
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
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
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
        val (status, json) = routes[key] ?: (500 to """{"code":"TEST_UNEXPECTED_ROUTE","message":"$key"}""")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        output.write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        socket.close()
        worker.join(5_000)
    }
}
