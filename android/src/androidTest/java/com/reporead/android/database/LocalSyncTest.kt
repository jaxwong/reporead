// Owns: Sync/Room/background integration coverage and its test-only loopback server; never the real data store.

package com.reporead.android.database

import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import com.reporead.android.BuildConfig
import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.StoredSession
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import com.reporead.android.network.describe
import com.reporead.android.network.testDisconnected
import com.reporead.android.network.testDocument
import com.reporead.android.network.testLibrary
import com.reporead.android.network.testRepository
import com.reporead.android.network.testReview
import com.reporead.android.network.wire
import com.reporead.android.network.wireJson
import com.reporead.android.reader.readerAssets
import com.reporead.android.reader.readerResource
import com.reporead.android.sync.NoteImages
import com.reporead.android.sync.SavingNotes
import com.reporead.android.sync.Sync
import com.reporead.android.sync.backgroundSync
import com.reporead.android.sync.saveAllInBackground
import com.reporead.android.sync.savingNotes
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
 * The sync's failure rules, the background sync's outcomes, and ordered local mutations, through production
 * Sync/Api/Room wiring against a test-only loopback HTTP server and an in-memory database; never the user's backend or
 * database.
 */
@RunWith(AndroidJUnit4::class)
class LocalSyncTest {
    private val sha = "a".repeat(40)
    private lateinit var store: RepoReadDatabase
    private lateinit var dao: RepoReadDao
    private lateinit var directory: File

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Room.inMemoryDatabaseBuilder(context, RepoReadDatabase::class.java).build()
        dao = store.dao()
        directory = File(context.cacheDir, "test-only-local-sync-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After
    fun close() {
        store.close()
        check(directory.deleteRecursively())
    }

    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    /** The server's library (GET /api/library) holding these lists' items, as JSON. */
    private fun library(
        repositories: String = "",
        readingStates: String = "",
        bookmarks: String = "",
        sessionLimit: Int = 40,
    ) =
        200 to
            """{"repositories":[$repositories],"readingStates":[$readingStates],"bookmarks":[$bookmarks],
            "notebook":{"annotations":[],"reviews":[],"sessionLimit":$sessionLimit}}"""

    /** A library repository [id] with one listed note and these [documents]. */
    private fun repository(id: Int, documents: String, snapshot: String = sha) =
        """{"id":$id,"githubRepositoryId":$id,"owner":"test-only","name":"r$id","defaultBranch":"main",
        "fullName":"test-only/r$id","documentCount":1,"lastSyncedCommitSha":"$snapshot","documents":[$documents]}"""

    /** A library note [id] at version [sha]. */
    private fun document(id: Int, deleted: Boolean = false) =
        """{"id":$id,"path":"n$id.md","title":"n$id","blobSha":"$sha","contentChangedAt":null,"deleted":$deleted}"""

    /** The server's page of note [id] at version [sha]. */
    private fun content(id: Int) =
        200 to
            """{"documentId":$id,"sourceBlobSha":"$sha","path":"n$id.md","title":"n$id","html":"<main id=note></main>","blocks":[],
            "renderFormat":${BuildConfig.RENDER_FORMAT},"snapshotCommitSha":"$sha"}"""

    /** What the folder screen shows for a Save all run in [state] with this [output] and [progress]. */
    private fun shown(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        savingNotes(WorkInfo(UUID.randomUUID(), state, emptySet(), output, progress))

    /** The empty library a sync ends with. */
    private val emptyLibrary = mapOf("GET /api/library" to library())

    private fun reading(documentId: Long) =
        ReadingStateEntity(
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

    private fun sync(url: String) =
        Sync(Api(url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, store, directory) {}

    /** A port nothing listens on. */
    private fun closedPort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun aFormatThreeCurrentPageIsFetchedOnlyOnceOnline() = runBlocking {
        savedRepository()
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n1.md", "n1", sha)))
        dao.saveNoteWithoutSearch(
            SavedPageEntity(1, sha, "n1.md", "n1", "<main>old presentation</main>", 0, 3, "b".repeat(40))
        )
        LoopbackServer(mapOf("GET /api/documents/1/content" to content(1))).use { server ->
            val sync = sync(server.url)
            val first = sync.openNote(1)
            assertEquals(BuildConfig.RENDER_FORMAT, first.note.renderFormat)
            assertNull(first.staleReason)
            assertEquals(first, sync.openNote(1))
            assertEquals(listOf("GET /api/documents/1/content"), server.requests)
        }
    }

    @Test
    fun aCurrentPageInAnOlderFormatOpensOfflineWithoutAWarning() = runBlocking {
        savedRepository()
        val old = SavedPageEntity(1, sha, "a.md", "a", "<main>kept</main>", 0, 3, sha)
        dao.saveNoteWithoutSearch(old)
        val opened = sync("http://127.0.0.1:${closedPort()}").openNote(1)
        assertEquals(old, opened.note)
        assertNull(opened.staleReason)
        assertEquals(old, dao.note(1))
    }

    @Test
    fun anOversizedNewVersionKeepsShowingTheSavedPageWithItsReason() = runBlocking {
        savedRepository()
        val old = SavedPageEntity(1, "b".repeat(40), "a.md", "a", "<main>kept</main>", 0, 3, sha)
        dao.saveNoteWithoutSearch(old)
        LoopbackServer(mapOf("GET /api/documents/1/content" to failure(422, "UNSUPPORTED_CONTENT"))).use { server ->
            val opened = sync(server.url).openNote(1)
            assertEquals(old, opened.note)
            assertEquals("UNSUPPORTED_CONTENT", opened.staleReason?.code)
            assertEquals(old, dao.note(1))
            assertEquals(listOf("GET /api/documents/1/content"), server.requests)
        }
    }

    @Test
    fun aMalformedReadingSaveFailsLoudlyWithoutSendingUnrelatedWork() = runBlocking {
        val pending = reading(1)
        dao.saveReading(pending)
        dao.saveBookmark(BookmarkEntity(2, bookmarked = true, pending = true, changedAt = 5))
        LoopbackServer(mapOf("PUT /api/documents/1/reading-state" to failure(400, "INVALID_REQUEST"))).use { server ->
            val error = runCatching { sync(server.url).syncWaitingChanges() }.exceptionOrNull()
            assertTrue("Expected a loud 400, got $error", error is ApiException && error.status == 400)
            assertEquals(pending, dao.reading(1))
            assertEquals(1, dao.pendingBookmarks().size)
            assertEquals(listOf("PUT /api/documents/1/reading-state"), server.requests)
        }
    }

    @Test
    fun aPositionOverTheOldLimitsIsSentOnceAndAcknowledgedWhileOtherWorkProceeds() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n.md", "Long heading note", sha)))
        val now = System.currentTimeMillis()
        val anchor =
            """{"headingPath":[${List(7) { "\"${"h".repeat(501)}\"" }.joinToString(",")}],"textPrefix":"${"p".repeat(201)}","blockIndex":4096}"""
        dao.saveReading(reading(1).copy(lastReadAt = now + 600_000, anchorJson = anchor))
        dao.saveBookmark(BookmarkEntity(2, bookmarked = true, pending = true, changedAt = 5))
        val accepted =
            JSONObject(serverReading)
                .put("lastReadAt", Instant.ofEpochMilli(now).toString())
                .put("anchor", JSONObject(anchor))
                .toString()
        LoopbackServer(
                mapOf(
                    "PUT /api/documents/1/reading-state" to (200 to accepted),
                    "PUT /api/documents/2/bookmark" to
                        (200 to """{"documentId":2,"createdAt":"1970-01-01T00:00:00Z"}"""),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                val result = sync.syncWaitingChanges()!!
                assertTrue(result.refused.isEmpty())
                assertNull(result.notSent)
                assertFalse(dao.reading(1)!!.pending)
                assertEquals(now, dao.reading(1)!!.lastReadAt)
                assertEquals(
                    wireJson.parseToJsonElement(anchor),
                    wireJson.parseToJsonElement(dao.reading(1)!!.anchorJson),
                )
                assertNull(sync.syncWaitingChanges())
                assertEquals(
                    listOf(
                        "PUT /api/documents/1/reading-state",
                        "PUT /api/documents/2/bookmark",
                    ),
                    server.requests,
                )
            }
    }

    @Test
    fun aReadingPositionNoLongerOwnedIsAcknowledgedAndDoesNotStopBookmarks() = runBlocking {
        dao.saveReading(reading(1))
        dao.saveBookmark(BookmarkEntity(2, bookmarked = true, pending = true, changedAt = 5))
        LoopbackServer(
                mapOf(
                    "PUT /api/documents/1/reading-state" to failure(404, "NOT_FOUND"),
                    "PUT /api/documents/2/bookmark" to
                        (200 to """{"documentId":2,"createdAt":"1970-01-01T00:00:00Z"}"""),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                assertTrue(sync.syncWaitingChanges()!!.refused.isEmpty())
                assertTrue(dao.pendingReading().isEmpty())
                assertTrue(dao.pendingBookmarks().isEmpty())
                assertNull(sync.syncWaitingChanges())
                assertEquals(
                    listOf("PUT /api/documents/1/reading-state", "PUT /api/documents/2/bookmark"),
                    server.requests,
                )
            }
    }

    @Test
    fun aRefusedBookmarkIsUndoneAndReportedWithItsDocumentAndReason() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "Bookmarked note", sha)))
        dao.saveBookmark(BookmarkEntity(1, bookmarked = true, pending = true, changedAt = 5))
        LoopbackServer(mapOf("PUT /api/documents/1/bookmark" to failure(404, "NOT_FOUND"))).use { server ->
            val sync = sync(server.url)
            val result = sync.syncWaitingChanges()!!
            val refused = result.refused.single()
            assertEquals(1L, refused.documentId)
            assertEquals("Bookmarked note", refused.title)
            assertEquals(404, refused.error.status)
            assertEquals("NOT_FOUND", refused.error.code)
            assertNull(result.notSent)
            assertNull(dao.bookmark(1).first())
            assertNull(sync.syncWaitingChanges())
            assertEquals(listOf("PUT /api/documents/1/bookmark"), server.requests)
        }
    }

    @Test
    fun creationsWaitWhenGitHubAccessFailsWhileDatabaseOnlyWorkStillSyncs() = runBlocking {
        dao.saveAnnotation(
            AnnotationEntity("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveAnnotation(
            AnnotationEntity(
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
            )
        )
        dao.saveReview(ReviewEntity("grade-of-unsent-card", "unsent-card", 4, 2, sha, pending = true))
        dao.saveReading(reading(1))
        LoopbackServer(
                emptyLibrary +
                    mapOf(
                        "POST /api/documents/1/annotations" to failure(403, "GITHUB_ACCESS_DENIED"),
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                    )
            )
            .use { server ->
                val result = sync(server.url).syncLocalChanges()
                assertEquals("GITHUB_ACCESS_DENIED", result.notSent?.code)
                // One creation attempt ends the GitHub-backed step; the grade waits for its card; the reading position
                // is sent.
                assertEquals(
                    listOf(
                        "POST /api/documents/1/annotations",
                        "PUT /api/documents/1/reading-state",
                        "GET /api/library",
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
            AnnotationEntity("unparsed", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveReading(reading(1))
        LoopbackServer(
                emptyLibrary +
                    mapOf(
                        "POST /api/documents/1/annotations" to (201 to """{"id":9}"""),
                        "PUT /api/documents/1/reading-state" to (200 to serverReading),
                    )
            )
            .use { server ->
                val result = sync(server.url).syncLocalChanges()
                assertEquals("INVALID_RESPONSE", result.notSent?.code)
                assertEquals(
                    listOf(
                        "POST /api/documents/1/annotations",
                        "PUT /api/documents/1/reading-state",
                        "GET /api/library",
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
            AnnotationEntity("deleted", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        sync("http://127.0.0.1:9").deleteAnnotation(dao.annotation("deleted")!!)
        assertTrue(dao.annotations(1).first().isEmpty())
        LoopbackServer(
                emptyLibrary +
                    mapOf(
                        "POST /api/documents/1/annotations" to (200 to serverHighlight("deleted")),
                        "DELETE /api/annotations/9?expectedVersion=1" to (204 to ""),
                    )
            )
            .use { server ->
                val result = sync(server.url).syncLocalChanges()
                assertNull(result.notSent)
                assertEquals(
                    listOf(
                        "POST /api/documents/1/annotations",
                        "DELETE /api/annotations/9?expectedVersion=1",
                        "GET /api/library",
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
            AnnotationEntity("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
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
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", sha)))
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "a.md", "a", "<html/>", 0))
        var requested = 0
        // Nothing listens on the port: saving a change never waits for the network.
        val sync =
            Sync(
                Api("http://127.0.0.1:${closedPort()}", { StoredSession.Readable("TEST_ONLY_SESSION") }) {},
                store,
                directory,
            ) {
                requested++
            }
        sync.saveReading(reading(1))
        assertEquals(1, requested)
        sync.setBookmark(1, true)
        assertEquals(2, requested)
        val passage = Passage(sha, "b1", 0, 4, "text")
        val highlight = sync.createAnnotation(passage, 1, null)!!
        assertEquals(3, requested)
        sync.deleteAnnotation(dao.annotation(highlight)!!)
        assertEquals(4, requested)
        // Practice makes a card and its first grade together.
        assertEquals(NewCardGrade.SAVED, sync.gradeNewCard(passage, 1, "Test-only question?", 4))
        assertEquals(5, requested)
        val card = dao.annotations(1).first().single { it.type == AnnotationType.CARD }.mutationId
        assertTrue(sync.recordReview(ReviewEntity("grade", card, 4, 1, sha, pending = true)))
        assertEquals(6, requested)
        sync.deleteAnnotation(dao.annotation(card)!!)
        assertEquals(7, requested)
        // A grade the card no longer accepts saves nothing, so there is nothing to send.
        assertFalse(sync.recordReview(ReviewEntity("stale", card, 4, 2, sha, pending = true)))
        assertEquals(7, requested)
    }

    @Test
    fun theBackgroundSyncMakesNoRequestWhenNothingWaits() = runBlocking {
        // A refused highlight is not waiting: sending it again cannot deliver it.
        dao.saveAnnotation(
            AnnotationEntity(
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
    fun annotationCapacityKeepsTheSameCreationPendingForAnotherSync() = runBlocking {
        val mutation = "test-only-capacity"
        val pending =
            AnnotationEntity(mutation, null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        dao.saveAnnotation(pending)
        val request = "POST /api/documents/1/annotations"
        LoopbackServer(mapOf(request to failure(422, "ANNOTATION_LIMIT"))).use { server ->
            repeat(2) {
                val error = runCatching { sync(server.url).pushAnnotations() }.exceptionOrNull() as ApiException
                assertEquals("ANNOTATION_LIMIT", error.code)
                assertEquals(pending, dao.annotation(mutation))
            }
            assertEquals(listOf(request, request), server.requests)
        }
    }

    /**
     * A reading pause asks for a background run, so it sends only what waits. Each send saves the server's answer, and
     * the screens pull the lists.
     */
    @Test
    fun aBackgroundRunSendsWhatWaitsAndPullsNothing() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n.md", "Test-only note", sha)))
        dao.saveReading(reading(1))
        LoopbackServer(emptyLibrary + mapOf("PUT /api/documents/1/reading-state" to (200 to serverReading))).use {
            server ->
            assertEquals(Result.success(), backgroundSync(sync(server.url), 0))
            assertEquals(listOf("PUT /api/documents/1/reading-state"), server.requests)
        }
        assertTrue(dao.pendingReading().isEmpty())
        assertEquals("the lists are left as they were", 1, dao.documents(7).first().size)
    }

    /** Only no answer at all is unreachable; an answer over the size limit keeps its HTTP status. */
    @Test
    fun onlyAServerThatDoesNotAnswerIsUnreachable() = runBlocking {
        // Just over Api's 8 MiB limit for a JSON response.
        val oversized = """{"repositories":[],"padding":"${"x".repeat(8 * 1024 * 1024)}"}"""
        val answered =
            LoopbackServer(mapOf("GET /api/library" to (200 to oversized))).use { server ->
                runCatching { Api(server.url, { StoredSession.Absent }) {}.fetchLibrarySnapshot() }.exceptionOrNull()
                    as ApiException
            }
        assertEquals("INVALID_RESPONSE", answered.code)
        assertEquals(200, answered.status)
        assertFalse(answered.unreachable)
        val silent =
            runCatching {
                Api("http://127.0.0.1:${closedPort()}", { StoredSession.Absent }) {}.fetchLibrarySnapshot()
            }
                .exceptionOrNull() as ApiException
        assertEquals(0, silent.status)
        assertTrue(silent.unreachable)
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
            AnnotationEntity("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveReading(reading(1))
        LoopbackServer(
                emptyLibrary +
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
            AnnotationEntity("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        LoopbackServer(
                emptyLibrary +
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
    fun saveAllSweepsReplacedImagesOnlyAfterTheBatchSucceeds() = runBlocking {
        val old = "b".repeat(40)
        dao.replaceRepositories(listOf(RepositoryEntity(7, "test-only/notes", 2, sha)))
        dao.replaceDocuments(
            7,
            listOf(DocumentEntity(1, 7, "n1.md", "n1", sha), DocumentEntity(2, 7, "n2.md", "n2", sha)),
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(1, old, "n1.md", "n1", "<main id=note></main>", 0, BuildConfig.RENDER_FORMAT, old)
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(2, old, "n2.md", "n2", "<main id=note></main>", 0, BuildConfig.RENDER_FORMAT, old)
        )
        val replaced = image(1, old)
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, "${document(1)},${document(2)}")),
                    "GET /api/documents/1/content" to content(1),
                    "GET /api/documents/2/content" to content(2),
                )
            )
            .use { server ->
                assertEquals(
                    Sync.SaveAllResult(2, 0, 0),
                    sync(server.url).saveAllNotes(7) { done, _ ->
                        assertTrue("Image sweep must wait until the batch ends; done=$done", replaced.exists())
                    },
                )
                assertFalse("A successful batch must retire the replaced snapshot", replaced.exists())
            }
    }

    /** Save all fetches a listed note unless its saved copy is its listed version and path, in this page format. */
    @Test
    fun saveAllFetchesOnlyTheNotesNotSavedAsListed() = runBlocking {
        val old = "b".repeat(40)
        dao.saveNoteWithoutSearch(
            SavedPageEntity(1, sha, "n1.md", "n1", "<main id=note></main>", 0, BuildConfig.RENDER_FORMAT, sha)
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(2, old, "n2.md", "n2", "<main id=note></main>", 0, BuildConfig.RENDER_FORMAT, old)
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(3, sha, "n3.md", "n3", "<main id=note></main>", 0, BuildConfig.RENDER_FORMAT - 1, old)
        )
        LoopbackServer(
                mapOf(
                    "GET /api/library" to
                        library(
                            repository(
                                7,
                                "${document(1)},${document(2)},${document(3)},${document(4)},${document(5, deleted = true)}",
                            )
                        ),
                    "GET /api/documents/2/content" to content(2),
                    "GET /api/documents/3/content" to content(3),
                    "GET /api/documents/4/content" to content(4),
                )
            )
            .use { server ->
                assertEquals(Sync.SaveAllResult(3, 1, 0), sync(server.url).saveAllNotes(7) { _, _ -> })
                assertEquals(
                    listOf(
                        "GET /api/library",
                        "GET /api/documents/2/content",
                        "GET /api/documents/3/content",
                        "GET /api/documents/4/content",
                    ),
                    server.requests,
                )
                assertEquals(Sync.SaveAllResult(0, 4, 0), sync(server.url).saveAllNotes(7) { _, _ -> })
                assertEquals(2, server.requests.count { it == "GET /api/library" })
                assertEquals(3, server.requests.count { it.endsWith("/content") })
            }
    }

    @Test
    fun saveAllSavesSnapshotImagesBeforeReportingProgressAndTheReaderCanOpenThemOffline() = runBlocking {
        val imagePath = "assets/C++ % 測.png"
        val imageSource = "/repo-image/assets/C++%20%25%20%E6%B8%AC.png"
        val html = """<main id="note"><img src="$imageSource"><img src="$imageSource"></main>"""
        val answer = JSONObject(content(1).second).put("html", html).toString()
        val imageRequest = "GET /api/documents/1/image?commitSha=$sha&path=assets%2FC%2B%2B+%25+%E6%B8%AC.png"
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, "${document(1)},${document(2)}")),
                    "GET /api/documents/1/content" to (200 to answer),
                    imageRequest to (200 to "TEST_ONLY_PNG"),
                    "GET /api/documents/2/content" to content(2),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                val progress = mutableListOf<Pair<Int, Int>>()
                assertEquals(
                    Sync.SaveAllResult(2, 0, 0),
                    sync.saveAllNotes(7) { done, total ->
                        progress += done to total
                        if (done == 1) assertTrue("Images must precede progress", imageRequest in server.requests)
                    },
                )
                assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
                assertEquals(
                    listOf(
                        "GET /api/library",
                        "GET /api/documents/1/content",
                        imageRequest,
                        "GET /api/documents/2/content",
                    ),
                    server.requests,
                )
                assertEquals(Sync.SaveAllResult(0, 2, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(1, server.requests.count { it == imageRequest })
            }
        val offlineImages =
            NoteImages(
                Api("http://127.0.0.1:1", { error("The saved image must not need a network request") }) {},
                directory,
            )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val response =
            readerResource(
                readerAssets(context),
                offlineImages,
                dao.note(1)!!,
                Uri.parse("https://appassets.androidplatform.net$imageSource"),
            )
        assertEquals(200, response.statusCode)
        assertEquals("TEST_ONLY_PNG", response.data.bufferedReader().use { it.readText() })
        assertEquals("TEST_ONLY_PNG", offlineImages.image(1, sha, imagePath)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun saveAllStopsOnAnImageServerFailureAndTheNextRunOnlyFetchesWhatIsStillMissing() = runBlocking {
        val html =
            """<main id="note"><img src="/repo-image/ok.png"><img src="/repo-image/missing.png">
            <img src="/repo-image/missing.png"><img src="https://test-only.invalid/remote.png">
            <span class="image-blocked">Image unavailable</span></main>"""
        val current = SavedPageEntity(1, sha, "n1.md", "n1", html, 0, BuildConfig.RENDER_FORMAT, sha)
        dao.saveNoteWithoutSearch(current)
        val listed =
            "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3, deleted = true)}"))
        val missing = "GET /api/documents/1/image?commitSha=$sha&path=missing.png"
        val ok = "GET /api/documents/1/image?commitSha=$sha&path=ok.png"
        LoopbackServer(
                mapOf(
                    listed,
                    missing to failure(503, "GITHUB_UNAVAILABLE"),
                    ok to (200 to "TEST_ONLY_OK"),
                    "GET /api/documents/2/content" to content(2),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                val progress = mutableListOf<Pair<Int, Int>>()
                val error = runCatching {
                    sync.saveAllNotes(7) { done, total -> progress += done to total }
                }
                    .exceptionOrNull()
                assertTrue("An image 503 must end this run, not report SaveAllResult", error is ApiException)
                assertEquals("GITHUB_UNAVAILABLE", (error as ApiException).code)
                assertEquals(listOf(0 to 2), progress)
                assertEquals(listOf("GET /api/library", ok, missing), server.requests)
                assertEquals(current, dao.note(1))
                assertNull(dao.note(2))
                assertEquals(Result.retry(), saveAllInBackground(sync, 7, 0) {})
                val last = saveAllInBackground(sync, 7, 9) {} as Result.Failure
                assertEquals(
                    SavingNotes.Stopped(0, 2, error.describe()),
                    shown(WorkInfo.State.FAILED, output = last.outputData),
                )
                assertEquals(
                    listOf("GET /api/library", ok, missing, "GET /api/library", missing, "GET /api/library", missing),
                    server.requests,
                )
            }
        LoopbackServer(
                mapOf(listed, missing to (200 to "TEST_ONLY_RECOVERED"), "GET /api/documents/2/content" to content(2))
            )
            .use { server ->
                val sync = sync(server.url)
                assertEquals(Sync.SaveAllResult(1, 1, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(listOf("GET /api/library", missing, "GET /api/documents/2/content"), server.requests)
                assertEquals("TEST_ONLY_OK", sync.images.image(1, sha, "ok.png")!!.toString(Charsets.UTF_8))
                assertEquals("TEST_ONLY_RECOVERED", sync.images.image(1, sha, "missing.png")!!.toString(Charsets.UTF_8))
                assertEquals(Sync.SaveAllResult(0, 2, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(
                    listOf("GET /api/library", missing, "GET /api/documents/2/content", "GET /api/library"),
                    server.requests,
                )
            }
    }

    @Test
    fun losingTheServerAfterACurrentPagesImagesStopsAtTheNextImageAndUsesTheWorkersRetryLimit() = runBlocking {
        val html = """<main id="note"><img src="/repo-image/a.png"></main>"""
        for (id in 1L..3L) dao.saveNoteWithoutSearch(
            SavedPageEntity(id, sha, "n$id.md", "n$id", html, 0, BuildConfig.RENDER_FORMAT, sha)
        )
        val first = "GET /api/documents/1/image?commitSha=$sha&path=a.png"
        for (attempt in listOf(0, 9)) {
            LoopbackServer(
                    mapOf(
                        "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3)}")),
                        first to (200 to "TEST_ONLY_FIRST"),
                    )
                )
                .use { server ->
                    val reads = AtomicInteger()
                    val sync =
                        Sync(
                            Api(
                                server.url,
                                {
                                    reads.incrementAndGet()
                                    StoredSession.Readable("TEST_ONLY_SESSION")
                                },
                            ) {},
                            store,
                            directory,
                        ) {}
                    val progress = mutableListOf<SavingNotes?>()
                    val result =
                        saveAllInBackground(sync, 7, attempt) { data ->
                            val state = shown(WorkInfo.State.RUNNING, progress = data)
                            progress += state
                            // Close the actual listener after a complete page, not a stubbed Api failure.
                            if (state == SavingNotes.Running(1, 3)) server.close()
                        }
                    assertEquals(listOf(SavingNotes.Running(0, 3), SavingNotes.Running(1, 3)), progress)
                    if (attempt == 0) assertEquals(Result.retry(), result)
                    else {
                        assertTrue("The last image attempt must stop with a reason", result is Result.Failure)
                        assertEquals(
                            SavingNotes.Stopped(1, 3, ApiException(0, "BACKEND_UNAVAILABLE", "Test-only").describe()),
                            shown(WorkInfo.State.FAILED, output = (result as Result.Failure).outputData),
                        )
                    }
                    assertEquals(if (attempt == 0) 3 else 2, reads.get())
                    assertEquals(
                        if (attempt == 0) listOf("GET /api/library", first) else listOf("GET /api/library"),
                        server.requests,
                    )
                    for (id in 1L..3L) assertEquals(html, dao.note(id)!!.html)
                }
        }
    }

    @Test
    fun anImage401EndsTheSessionAndStopsWithoutRequestingLaterImages() = runBlocking {
        val html = """<main id="note"><img src="/repo-image/a.png"></main>"""
        for (id in 1L..3L) dao.saveNoteWithoutSearch(
            SavedPageEntity(id, sha, "n$id.md", "n$id", html, 0, BuildConfig.RENDER_FORMAT, sha)
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = "test-only-save-all-session-${UUID.randomUUID()}"
        val sessions = SessionStore(context, prefs)
        sessions.save("TEST_ONLY_SESSION")
        val first = "GET /api/documents/1/image?commitSha=$sha&path=a.png"
        val refused = "GET /api/documents/2/image?commitSha=$sha&path=a.png"
        try {
            LoopbackServer(
                    mapOf(
                        "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3)}")),
                        first to (200 to "TEST_ONLY_FIRST"),
                        refused to (401 to ""),
                    )
                )
                .use { server ->
                    val sync = Sync(Api(server.url, sessions::read, sessions::endRefused), store, directory) {}
                    val progress = mutableListOf<SavingNotes?>()
                    val result =
                        saveAllInBackground(sync, 7, 0) { progress += shown(WorkInfo.State.RUNNING, progress = it) }
                    assertTrue("An image 401 must stop, not report Saved", result is Result.Failure)
                    assertEquals(
                        SavingNotes.Stopped(1, 3, ApiException(401, "HTTP_401", "Test-only").describe()),
                        shown(WorkInfo.State.FAILED, output = (result as Result.Failure).outputData),
                    )
                    assertEquals(listOf(SavingNotes.Running(0, 3), SavingNotes.Running(1, 3)), progress)
                    assertFalse(sessions.hasSession)
                    assertEquals(StoredSession.Absent, sessions.read())
                    assertEquals(listOf("GET /api/library", first, refused), server.requests)
                    for (id in 1L..3L) assertEquals(html, dao.note(id)!!.html)
                    assertEquals("TEST_ONLY_FIRST", sync.images.image(1, sha, "a.png")!!.toString(Charsets.UTF_8))
                }
        } finally {
            check(context.deleteSharedPreferences(prefs))
        }
    }

    @Test
    fun permanentImageRefusalsCountEachPageOnceAndKeepItsTextBeforeContinuingAtTheNextPage() = runBlocking {
        val html = """<main id="note"><img src="/repo-image/refused.png"><img src="/repo-image/later.png"></main>"""
        for (id in 2L..3L) dao.saveNoteWithoutSearch(
            SavedPageEntity(id, sha, "n$id.md", "n$id", html, 0, BuildConfig.RENDER_FORMAT, sha)
        )
        val requests = (1..3).map { "GET /api/documents/$it/image?commitSha=$sha&path=refused.png" }
        LoopbackServer(
                mapOf(
                    "GET /api/library" to
                        library(repository(7, "${document(1)},${document(2)},${document(3)},${document(4)}")),
                    "GET /api/documents/1/content" to
                        (200 to JSONObject(content(1).second).put("html", html).toString()),
                    requests[0] to failure(404, "IMAGE_NOT_FOUND"),
                    requests[1] to failure(400, "INVALID_IMAGE_PATH"),
                    requests[2] to failure(422, "UNSUPPORTED_CONTENT"),
                    "GET /api/documents/4/content" to content(4),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                val progress = mutableListOf<Pair<Int, Int>>()
                assertEquals(
                    Sync.SaveAllResult(1, 0, 3),
                    sync.saveAllNotes(7) { done, total -> progress += done to total },
                )
                assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
                for (id in 1L..3L) assertEquals(html, dao.note(id)!!.html)
                assertEquals(
                    listOf("GET /api/library", "GET /api/documents/1/content") +
                        requests +
                        "GET /api/documents/4/content",
                    server.requests,
                )
                assertEquals(Sync.SaveAllResult(0, 1, 3), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(1, server.requests.count { it == "GET /api/documents/1/content" })
                assertEquals(2, server.requests.count { it == "GET /api/library" })
                assertTrue(server.requests.none { it.contains("later.png") })
            }
    }

    @Test
    fun cancellingSaveAllDuringAnImageKeepsItAndTheNextRunFetchesOnlyTheRemainingImage() = runBlocking {
        val html = """<main id="note"><img src="/repo-image/first.png"><img src="/repo-image/second.png"></main>"""
        val current = SavedPageEntity(1, sha, "n1.md", "n1", html, 0, BuildConfig.RENDER_FORMAT, sha)
        val old = "b".repeat(40)
        dao.saveNoteWithoutSearch(current.copy(snapshotCommitSha = old))
        val replaced = image(1, old)
        val listed = "GET /api/library" to library(repository(7, document(1)))
        val contentRequest = "GET /api/documents/1/content"
        val first = "GET /api/documents/1/image?commitSha=$sha&path=first.png"
        val second = "GET /api/documents/1/image?commitSha=$sha&path=second.png"
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(
                mapOf(
                    listed,
                    contentRequest to (200 to JSONObject(content(1).second).put("html", html).toString()),
                    first to (200 to "TEST_ONLY_FIRST"),
                )
            ) { request ->
                if (request == first) {
                    arrived.countDown()
                    check(release.await(10, SECONDS)) { "Test never released the held image" }
                }
            }
            .use { server ->
                val saving = launch(Dispatchers.IO) { sync(server.url).saveAllNotes(7) { _, _ -> } }
                val saved: SavedPageEntity
                try {
                    assertTrue("The image was never requested", arrived.await(10, SECONDS))
                    saved = checkNotNull(dao.note(1))
                    assertEquals(sha, saved.snapshotCommitSha)
                    assertEquals(html, saved.html)
                    saving.cancel()
                } finally {
                    release.countDown()
                }
                saving.join()
                assertTrue(saving.isCancelled)
                assertEquals(listOf("GET /api/library", contentRequest, first), server.requests)
                assertEquals(saved, dao.note(1))
                assertTrue("Cancellation must leave the orphan for a later sweep", replaced.exists())
            }
        LoopbackServer(mapOf(listed, second to (200 to "TEST_ONLY_SECOND"))).use { server ->
            val sync = sync(server.url)
            assertEquals(Sync.SaveAllResult(0, 1, 0), sync.saveAllNotes(7) { _, _ -> })
            assertEquals(listOf("GET /api/library", second), server.requests)
            assertFalse("The next run's library refresh sweeps the replaced snapshot", replaced.exists())
            assertEquals("TEST_ONLY_FIRST", sync.images.image(1, sha, "first.png")!!.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun saveAllWithNoListedNotesMakesOnlyTheLibraryCallOnEachRun() = runBlocking {
        LoopbackServer(emptyLibrary).use { server ->
            repeat(2) {
                val progress = mutableListOf<Pair<Int, Int>>()
                assertEquals(
                    Sync.SaveAllResult(0, 0, 0),
                    sync(server.url).saveAllNotes(7) { done, total -> progress += done to total },
                )
                assertEquals(listOf(0 to 0), progress)
            }
            assertEquals(listOf("GET /api/library", "GET /api/library"), server.requests)
        }
    }

    @Test
    fun disconnectDuringImagePrefetchWaitsForThePageThenRemovesItsImages() = runBlocking {
        val html = """<main id="note"><img src="/repo-image/first.png"><img src="/repo-image/second.png"></main>"""
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "n1.md", "n1", html, 0, BuildConfig.RENDER_FORMAT, sha))
        val first = "GET /api/documents/1/image?commitSha=$sha&path=first.png"
        val second = "GET /api/documents/1/image?commitSha=$sha&path=second.png"
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, document(1))),
                    first to (200 to "TEST_ONLY_FIRST"),
                    second to (200 to "TEST_ONLY_SECOND"),
                    "DELETE /api/repositories/7" to (200 to wire(testDisconnected())),
                )
            ) { request ->
                if (request == first) {
                    arrived.countDown()
                    check(release.await(10, SECONDS)) { "Test never released the held image" }
                }
            }
            .use { server ->
                val sync = sync(server.url)
                val saving = async(Dispatchers.IO) { sync.saveAllNotes(7) { _, _ -> } }
                try {
                    assertTrue("The image was never requested", arrived.await(10, SECONDS))
                    val disconnecting = async(start = CoroutineStart.UNDISPATCHED) { sync.disconnect(7) }
                    assertEquals(listOf("GET /api/library", first), server.requests)
                    release.countDown()
                    assertEquals(Sync.SaveAllResult(0, 1, 0), saving.await())
                    disconnecting.await()
                } finally {
                    release.countDown()
                }
                assertEquals(listOf("GET /api/library", first, second, "DELETE /api/repositories/7"), server.requests)
                assertNull(dao.note(1))
                assertFalse(File(directory, "images/1").exists())
            }
    }

    @Test
    fun saveAllSkipsUnsupportedPagesKeepsOlderCopiesAndReadsTheCompleteSizeLimitOnBothRuns() = runBlocking {
        // Synthetic page at the server's 1 MiB complete-string limit: HTML, search blocks, SHA, path and title.
        val path = "n3.md"
        val title = "n3"
        val text = "測😀".repeat(10_000)
        val prefix = "<main id=note>"
        val suffix = "</main>"
        val fixedBytes = (sha + sha + path + title + text + prefix + suffix).toByteArray().size
        val html = prefix + "x".repeat(1_048_576 - fixedBytes) + suffix
        val content =
            JSONObject()
                .put("documentId", 3)
                .put("sourceBlobSha", sha)
                .put("path", path)
                .put("title", title)
                .put("html", html)
                .put("blocks", JSONArray().put(JSONObject().put("id", "b0").put("text", text)))
                .put("renderFormat", BuildConfig.RENDER_FORMAT)
                .put("snapshotCommitSha", sha)
                .toString()
        val old = SavedPageEntity(1, "b".repeat(40), "n1.md", "n1", "<main>kept</main>", 0, BuildConfig.RENDER_FORMAT)
        dao.saveNoteWithoutSearch(old)
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3)}")),
                    "GET /api/documents/1/content" to failure(422, "UNSUPPORTED_CONTENT"),
                    "GET /api/documents/2/content" to failure(422, "UNSUPPORTED_CONTENT"),
                    "GET /api/documents/3/content" to (200 to content),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                val progress = mutableListOf<Pair<Int, Int>>()
                assertEquals(
                    Sync.SaveAllResult(1, 0, 2),
                    sync.saveAllNotes(7) { done, total -> progress += done to total },
                )
                assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
                val expected = SavedPageEntity(3, sha, path, title, html, 0, BuildConfig.RENDER_FORMAT, sha)
                repeat(2) {
                    assertEquals(old, dao.note(1))
                    assertNull(dao.note(2))
                    assertEquals(expected, dao.note(3)!!.copy(fetchedAt = 0))
                }
                assertEquals(
                    Sync.SaveAllResult(0, 1, 2),
                    sync.saveAllNotes(7) { _, _ -> },
                )
                assertEquals(old, dao.note(1))
                assertEquals(expected, dao.note(3)!!.copy(fetchedAt = 0))
                assertEquals(
                    listOf(
                        "GET /api/library",
                        "GET /api/documents/1/content",
                        "GET /api/documents/2/content",
                        "GET /api/documents/3/content",
                        "GET /api/library",
                        "GET /api/documents/1/content",
                        "GET /api/documents/2/content",
                    ),
                    server.requests,
                )
            }
    }

    /** Save all runs as background work; what it reports is what the folder screen shows, on any screen. */
    @Test
    fun aBackgroundSaveAllReportsItsProgressAndWhatItSaved() = runBlocking {
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, "${document(1)},${document(2)}")),
                    "GET /api/documents/1/content" to content(1),
                    "GET /api/documents/2/content" to failure(422, "UNSUPPORTED_CONTENT"),
                )
            )
            .use { server ->
                val progress = mutableListOf<SavingNotes?>()
                val result =
                    saveAllInBackground(sync(server.url), 7, 0) {
                        progress += shown(WorkInfo.State.RUNNING, progress = it)
                    }
                assertEquals(
                    listOf(SavingNotes.Running(0, 2), SavingNotes.Running(1, 2), SavingNotes.Running(2, 2)),
                    progress,
                )
                assertEquals(
                    SavingNotes.Saved(1, 0, 1),
                    shown(WorkInfo.State.SUCCEEDED, output = (result as Result.Success).outputData),
                )
            }
    }

    /**
     * A run that ends part-way — a retryable failure here; Android stopping it after about 10 minutes is the same —
     * keeps what it saved, and the next run fetches only the rest.
     */
    @Test
    fun aBackgroundSaveAllTriedAgainFetchesOnlyTheNotesNotYetSaved() = runBlocking {
        val listed = "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3)}"))
        LoopbackServer(
                mapOf(
                    listed,
                    "GET /api/documents/1/content" to content(1),
                    "GET /api/documents/2/content" to failure(503, "GITHUB_UNAVAILABLE"),
                )
            )
            .use { server ->
                assertEquals(Result.retry(), saveAllInBackground(sync(server.url), 7, 0) {})
                assertEquals(
                    listOf("GET /api/library", "GET /api/documents/1/content", "GET /api/documents/2/content"),
                    server.requests,
                )
            }
        LoopbackServer(
                mapOf(
                    listed,
                    "GET /api/documents/2/content" to content(2),
                    "GET /api/documents/3/content" to content(3),
                )
            )
            .use { server ->
                val result = saveAllInBackground(sync(server.url), 7, 1) {}
                assertEquals(
                    SavingNotes.Saved(2, 1, 0),
                    shown(WorkInfo.State.SUCCEEDED, output = (result as Result.Success).outputData),
                )
                assertEquals(
                    listOf("GET /api/library", "GET /api/documents/2/content", "GET /api/documents/3/content"),
                    server.requests,
                )
            }
    }

    @Test
    fun aLongServerFailureStillReportsTheSavedNotesAndABoundedReason() = runBlocking {
        val message = "Test-only server error: " + "界".repeat(11_000)
        val error = ApiException(403, "GITHUB_ACCESS_DENIED", message)
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3)}")),
                    "GET /api/documents/1/content" to content(1),
                    "GET /api/documents/2/content" to
                        (403 to JSONObject().put("code", error.code).put("message", message).toString()),
                )
            )
            .use { server ->
                val result = saveAllInBackground(sync(server.url), 7, 0) {} as Result.Failure
                val stopped = shown(WorkInfo.State.FAILED, output = result.outputData) as SavingNotes.Stopped
                assertEquals(1, stopped.done)
                assertEquals(3, stopped.totalNotes)
                val reason = stopped.reason!!
                assertTrue(reason.endsWith("…"))
                assertTrue(error.describe().startsWith(reason.removeSuffix("…")))
                assertTrue(reason.toByteArray(Charsets.UTF_8).size < Data.MAX_DATA_BYTES)
                assertEquals("n1", dao.note(1)?.title)
                assertNull(dao.note(2))
            }
    }

    /**
     * A failure that trying again cannot fix, or a retryable one on the last attempt, ends Save all with how far it
     * got.
     */
    @Test
    fun aBackgroundSaveAllThatCannotGoOnSaysHowFarItGotAndWhy() = runBlocking {
        val listed = "GET /api/library" to library(repository(7, "${document(1)},${document(2)},${document(3)}"))
        LoopbackServer(
                mapOf(
                    listed,
                    "GET /api/documents/1/content" to content(1),
                    "GET /api/documents/2/content" to failure(403, "GITHUB_ACCESS_DENIED"),
                )
            )
            .use { server ->
                val result = saveAllInBackground(sync(server.url), 7, 0) {}
                assertEquals(
                    SavingNotes.Stopped(
                        1,
                        3,
                        ApiException(403, "GITHUB_ACCESS_DENIED", "Test-only GITHUB_ACCESS_DENIED").describe(),
                    ),
                    shown(WorkInfo.State.FAILED, output = (result as Result.Failure).outputData),
                )
            }
        val unreachable = sync("http://127.0.0.1:${closedPort()}")
        assertEquals(Result.retry(), saveAllInBackground(unreachable, 7, 8) {})
        val last = saveAllInBackground(unreachable, 7, 9) {} as Result.Failure
        assertEquals(0, (shown(WorkInfo.State.FAILED, output = last.outputData) as SavingNotes.Stopped).totalNotes)
        // A run that failed in RepoRead itself (WorkManager records no output) is shown without a server reason.
        assertEquals(SavingNotes.Stopped(0, 0, null), shown(WorkInfo.State.FAILED))
        assertEquals(SavingNotes.Waiting, shown(WorkInfo.State.ENQUEUED))
        // Sign-out and account deletion cancel Save all: the screen shows nothing of a removed copy.
        assertNull(shown(WorkInfo.State.CANCELLED))
    }

    @Test
    fun saveAllAndOpenKeepTextOnlyPagesAcrossSnapshotChanges() = runBlocking {
        val newCommit = "d".repeat(40)
        for (savedCommit in listOf(sha, null)) {
            val saved =
                SavedPageEntity(
                    1,
                    sha,
                    "n1.md",
                    "n1",
                    "<main>saved text</main>",
                    0,
                    BuildConfig.RENDER_FORMAT,
                    savedCommit,
                )
            dao.saveNoteWithoutSearch(saved)
            LoopbackServer(mapOf("GET /api/library" to library(repository(7, document(1), newCommit)))).use { server ->
                val sync = sync(server.url)
                val progress = mutableListOf<Pair<Int, Int>>()
                assertEquals(
                    Sync.SaveAllResult(0, 1, 0),
                    sync.saveAllNotes(7) { done, total -> progress += done to total },
                )
                assertEquals(listOf(0 to 1, 1 to 1), progress)
                assertEquals(Sync.Opened(saved, null), sync.openNote(1))
                assertEquals(Sync.SaveAllResult(0, 1, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(saved, dao.note(1))
                assertEquals(listOf("GET /api/library", "GET /api/library"), server.requests)
            }
        }
    }

    @Test
    fun saveAllRebindsABlockedEmbedAfterASnapshotChange() = runBlocking {
        val newCommit = "d".repeat(40)
        val saved =
            SavedPageEntity(
                1,
                sha,
                "n1.md",
                "n1",
                """<main><span class="image-blocked">Image unavailable</span></main>""",
                0,
                BuildConfig.RENDER_FORMAT,
                sha,
            )
        dao.saveNoteWithoutSearch(saved)
        val html = """<main><img src="/repo-image/added.png"></main>"""
        val answer = JSONObject(content(1).second).put("snapshotCommitSha", newCommit).put("html", html).toString()
        val imageRequest = "GET /api/documents/1/image?commitSha=$newCommit&path=added.png"
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, document(1), newCommit)),
                    "GET /api/documents/1/content" to (200 to answer),
                    imageRequest to (200 to "TEST_ONLY_ADDED_IMAGE"),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                assertEquals(Sync.SaveAllResult(1, 0, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(html, dao.note(1)!!.html)
                assertEquals(newCommit, dao.note(1)!!.snapshotCommitSha)
                assertEquals(Sync.SaveAllResult(0, 1, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(
                    listOf("GET /api/library", "GET /api/documents/1/content", imageRequest, "GET /api/library"),
                    server.requests,
                )
            }
    }

    @Test
    fun openRefreshesImageDependentPagesWithAnOldOrUnknownSnapshot() = runBlocking {
        val newCommit = "d".repeat(40)
        dao.replaceRepositories(listOf(RepositoryEntity(7, "test-only/r7", 1, newCommit)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n1.md", "n1", sha)))
        for (html in
            listOf(
                """<main><img src="/repo-image/chart.png"></main>""",
                """<main><span class="image-blocked">Image unavailable</span></main>""",
            )) {
            for (savedCommit in listOf(sha, null)) {
                dao.saveNoteWithoutSearch(
                    SavedPageEntity(1, sha, "n1.md", "n1", html, 0, BuildConfig.RENDER_FORMAT, savedCommit)
                )
                val answer =
                    JSONObject(content(1).second).put("snapshotCommitSha", newCommit).put("html", html).toString()
                LoopbackServer(mapOf("GET /api/documents/1/content" to (200 to answer))).use { server ->
                    val sync = sync(server.url)
                    val opened = sync.openNote(1)
                    assertEquals(newCommit, opened.note.snapshotCommitSha)
                    assertEquals(html, opened.note.html)
                    assertNull(opened.staleReason)
                    assertEquals(opened, sync.openNote(1))
                    assertEquals(listOf("GET /api/documents/1/content"), server.requests)
                }
            }
        }
    }

    @Test
    fun anImageOnlySnapshotChangeRefreshesThePageAndRetiresOldImagesWithoutChangingTheDisplayedCopy() = runBlocking {
        val oldCommit = "b".repeat(40)
        val newCommit = "d".repeat(40)
        dao.replaceRepositories(listOf(RepositoryEntity(7, "test-only/r7", 1, oldCommit)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n1.md", "n1", sha)))
        val displayed =
            SavedPageEntity(
                1,
                sha,
                "n1.md",
                "n1",
                "<main>saved text<img src=\"/repo-image/old/chart.png\"></main>",
                0,
                BuildConfig.RENDER_FORMAT,
                oldCommit,
            )
        dao.saveNoteWithoutSearch(displayed)
        val oldImage = image(1, oldCommit)
        val html = "<main>saved text<img src=\"/repo-image/new/chart.png\"></main>"
        val answer =
            JSONObject()
                .put("sourceBlobSha", sha)
                .put("documentId", 1)
                .put("snapshotCommitSha", newCommit)
                .put("path", "n1.md")
                .put("title", "n1")
                .put("html", html)
                .put("blocks", JSONArray().put(JSONObject().put("id", "b0").put("text", "saved text")))
                .put("renderFormat", BuildConfig.RENDER_FORMAT)
                .toString()
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(repository(7, document(1), newCommit)),
                    "GET /api/documents/1/content" to (200 to answer),
                    "GET /api/documents/1/image?commitSha=$newCommit&path=new%2Fchart.png" to
                        (200 to "TEST_ONLY_NEW_IMAGE"),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                assertEquals(displayed, sync.openNote(1).note)
                assertTrue(server.requests.isEmpty())
                assertEquals(Sync.SaveAllResult(1, 0, 0), sync.saveAllNotes(7) { _, _ -> })
                val saved = dao.note(1)!!
                assertEquals(sha, saved.blobSha)
                assertEquals(newCommit, saved.snapshotCommitSha)
                assertEquals(html, saved.html)
                assertFalse(oldImage.exists())
                assertEquals(oldCommit, displayed.snapshotCommitSha)
                assertEquals(saved, sync.openNote(1).note)
                assertEquals(Sync.SaveAllResult(0, 1, 0), sync.saveAllNotes(7) { _, _ -> })
                assertEquals(
                    listOf(
                        "GET /api/library",
                        "GET /api/documents/1/content",
                        "GET /api/documents/1/image?commitSha=$newCommit&path=new%2Fchart.png",
                        "GET /api/library",
                    ),
                    server.requests,
                )
            }
    }

    @Test
    fun missingOrMalformedImageSnapshotMetadataCannotReplaceALegacySavedPage() = runBlocking {
        val saved =
            SavedPageEntity(
                1,
                sha,
                "n1.md",
                "n1",
                "<main>kept<img src=\"/repo-image/chart.png\"></main>",
                0,
                BuildConfig.RENDER_FORMAT,
            )
        dao.replaceRepositories(listOf(RepositoryEntity(7, "test-only/r7", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n1.md", "n1", sha)))
        dao.saveNoteWithoutSearch(saved)
        for (value in listOf(null, JSONObject.NULL, "INVALID", "A".repeat(40))) {
            val answer =
                JSONObject()
                    .put("sourceBlobSha", sha)
                    .put("documentId", 1)
                    .put("path", "n1.md")
                    .put("title", "n1")
                    .put("html", "<main>new</main>")
                    .put("blocks", JSONArray().put(JSONObject().put("id", "b0").put("text", "new")))
                    .put("renderFormat", BuildConfig.RENDER_FORMAT)
            if (value != null) answer.put("snapshotCommitSha", value)
            LoopbackServer(mapOf("GET /api/documents/1/content" to (200 to answer.toString()))).use { server ->
                val opened = sync(server.url).openNote(1)
                assertEquals(saved, opened.note)
                assertTrue(opened.staleReason.toString(), opened.staleReason != null)
                assertEquals(200, opened.staleReason?.status)
                assertEquals("INVALID_RESPONSE", opened.staleReason?.code)
                assertEquals(saved, dao.note(1))
                assertEquals(listOf("GET /api/documents/1/content"), server.requests)
            }
        }
    }

    @Test
    fun aSyncLoadsEveryRepositorysNotesSoARemovedNoteWithKeptDataIsNamed() = runBlocking {
        val readingOfTheRemovedNote = serverReading.replace("\"documentId\":1", "\"documentId\":2")
        LoopbackServer(
                mapOf(
                    "GET /api/library" to
                        library(
                            repository(7, "${document(1)},${document(2, deleted = true)}"),
                            readingStates = readingOfTheRemovedNote,
                        )
                )
            )
            .use { server ->
                sync(server.url).syncLocalChanges()
                assertEquals(listOf("GET /api/library"), server.requests)
                assertEquals(listOf(1L), dao.documents(7).first().map { it.id })
                assertEquals(
                    listOf(ReadNote(2, "n2", "n2.md", deleted = true, progressPercent = 50)),
                    dao.recentReading(5).first(),
                )
            }
    }

    /**
     * The saved lists are all from one library answer or all as they were: a library request that fails, or an answer
     * whose last part breaks the contract, writes none of them and deletes no image.
     */
    @Test
    fun aBrokenReadingSaveNeverAcknowledgesThePendingPosition() = runBlocking {
        for (answer in
            listOf(
                serverReading.replace("1970-01-01T00:00:01Z", "+1000000000-12-31T23:59:59.999999999Z"),
                serverReading.replace("1970-01-01T00:00:01Z", "not-a-time"),
                serverReading.replace("\"documentId\":1", "\"documentId\":2"),
                serverReading.replace("\"blockIndex\":0", "\"blockIndex\":\"0\""),
                serverReading.replace("\"blockIndex\":0", "\"other\":0"),
                serverReading.replace("\"progressPercent\":50", "\"progressPercent\":12.7"),
            )) {
            val pending = reading(1)
            dao.saveReading(pending)
            LoopbackServer(mapOf("PUT /api/documents/1/reading-state" to (200 to answer))).use { server ->
                val error = runCatching { sync(server.url).syncWaitingChanges() }.exceptionOrNull()
                assertTrue(
                    "Expected INVALID_RESPONSE, got $error",
                    error is ApiException && error.code == "INVALID_RESPONSE",
                )
                assertEquals(pending, dao.reading(1))
                assertEquals(listOf("PUT /api/documents/1/reading-state"), server.requests)
            }
        }
    }

    @Test
    fun anUncertainBookmarkAnswerNeverDropsThePendingToggle() = runBlocking {
        for ((status, body) in
            listOf(
                409 to """{"code":7,"message":"Test-only malformed code"}""",
                409 to """{"code":"","message":"Test-only blank code"}""",
                409 to """{"code":"UNKNOWN_TEST_ONLY_CODE","message":"Test-only unknown code"}""",
                409 to """{"code":"NOT_FOUND","message":"Test-only wrong status"}""",
                409 to "x".repeat(8 * 1024 * 1024 + 1),
                200 to """{"documentId":2,"createdAt":"1970-01-01T00:00:00Z"}""",
            )) {
            val pending = BookmarkEntity(1, bookmarked = true, pending = true, changedAt = 5)
            dao.saveBookmark(pending)
            LoopbackServer(mapOf("PUT /api/documents/1/bookmark" to (status to body))).use { server ->
                val error = runCatching { sync(server.url).syncWaitingChanges() }.exceptionOrNull()
                assertTrue("Expected an uncertain failure, got $error", error is ApiException)
                assertEquals(listOf(pending), dao.pendingBookmarks())
                assertEquals(listOf("PUT /api/documents/1/bookmark"), server.requests)
            }
        }
    }

    @Test
    fun aReviewAnswerMustMatchItsCardAndCanReplaceTheLocalTimestamp() = runBlocking {
        dao.saveAnnotation(
            AnnotationEntity(
                "test-only-card",
                9,
                1,
                sha,
                "b1",
                0,
                4,
                "text",
                null,
                1,
                0,
                AnnotationState.ACKNOWLEDGED,
                type = AnnotationType.CARD,
                question = "Test-only question?",
            )
        )
        val pending = ReviewEntity("test-only-grade", "test-only-card", 4, 1_000, sha, true)
        dao.saveReview(pending)
        val body =
            """{"mutationId":"test-only-grade","cardMutationId":"test-only-other-card","grade":4,"reviewedAt":"1970-01-01T00:00:01Z","blobSha":"$sha"}"""
        LoopbackServer(mapOf("POST /api/annotations/9/reviews" to (200 to body))).use { server ->
            val error = runCatching { sync(server.url).syncWaitingChanges() }.exceptionOrNull() as ApiException
            assertEquals("INVALID_RESPONSE", error.code)
            assertEquals(listOf(pending), dao.pendingReviews())
            assertEquals(listOf("POST /api/annotations/9/reviews"), server.requests)
        }
        val clamped =
            body
                .replace("test-only-other-card", "test-only-card")
                .replace("1970-01-01T00:00:01Z", "1970-01-01T00:00:00Z")
        LoopbackServer(mapOf("POST /api/annotations/9/reviews" to (200 to clamped))).use { server ->
            sync(server.url).syncWaitingChanges()
            sync(server.url).syncWaitingChanges()
            assertTrue(dao.pendingReviews().isEmpty())
            assertEquals(listOf(pending.copy(reviewedAt = 0, pending = false)), dao.reviews().first())
            assertEquals(listOf("POST /api/annotations/9/reviews"), server.requests)
        }
    }

    @Test
    fun anUncertainDeletionStaysHiddenAndPendingUntilAConfirmedSecondRun() = runBlocking {
        val mutation = "test-only-deletion"
        dao.saveAnnotation(
            AnnotationEntity(mutation, null, 1, sha, "b1", 0, 4, "text", null, 1, 0, AnnotationState.UNSENT)
        )
        sync("http://127.0.0.1:1").deleteAnnotation(checkNotNull(dao.annotation(mutation)))
        LoopbackServer(mapOf("POST /api/documents/1/annotations" to failure(500, "ANNOTATION_DELETED"))).use { server ->
            val error = runCatching { sync(server.url).pushAnnotations() }.exceptionOrNull() as ApiException
            assertEquals(500, error.status)
            assertEquals(AnnotationState.DELETING, dao.annotation(mutation)?.state)
            assertEquals(1, server.requests.size)
        }
        LoopbackServer(
                mapOf(
                    "POST /api/documents/1/annotations" to (200 to serverHighlight(mutation)),
                    "DELETE /api/annotations/9?expectedVersion=1" to (200 to "{}"),
                )
            )
            .use { server ->
                val error = runCatching { sync(server.url).pushAnnotations() }.exceptionOrNull() as ApiException
                assertEquals("INVALID_RESPONSE", error.code)
                assertEquals(AnnotationState.DELETING, dao.annotation(mutation)?.state)
                assertTrue(dao.annotations(1).first().isEmpty())
                assertEquals(2, server.requests.size)
            }
        LoopbackServer(mapOf("DELETE /api/annotations/9?expectedVersion=1" to (204 to ""))).use { server ->
            sync(server.url).pushAnnotations()
            sync(server.url).pushAnnotations()
            assertNull(dao.annotation(mutation))
            assertEquals(listOf("DELETE /api/annotations/9?expectedVersion=1"), server.requests)
        }
    }

    @Test
    fun aLibraryRequestThatFailsLeavesEverySavedListAndImageAsItWas() = runBlocking {
        dao.replaceRepositories(
            listOf(RepositoryEntity(7, "test-only/kept", 1, sha), RepositoryEntity(9, "test-only/gone", 1, sha))
        )
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", sha)))
        dao.replaceDocuments(9, listOf(DocumentEntity(3, 9, "c.md", "c", sha)))
        dao.saveNoteWithoutSearch(SavedPageEntity(3, sha, "c.md", "c", "<html/>", 0))
        dao.saveReading(reading(1).copy(pending = false))
        dao.saveBookmark(BookmarkEntity(1, bookmarked = true, pending = false, changedAt = 5))
        val image = File(directory, "images/3/$sha/test-only").apply { check(parentFile!!.mkdirs()) }
        image.writeText("test-only image")
        suspend fun saved() =
            listOf(
                dao.repositories().first(),
                dao.documents(7).first(),
                dao.documents(9).first(),
                dao.note(3),
                dao.reading(1),
                dao.bookmarks().first(),
                dao.notebook().first(),
            )
        val before = saved()
        // Repository 9 is gone, 8 is new, and every other list is empty: a complete answer would change every saved
        // list.
        val repositories = "${repository(7, document(2))},${repository(8, document(4))}"
        for ((answer, code) in
            listOf(
                failure(500, "TEST_ONLY_FAILURE") to "TEST_ONLY_FAILURE",
                (200 to
                    wire(
                        testLibrary(
                            repositories =
                                listOf(testRepository(listOf(testDocument(), testDocument().copy(path = "other.md"))))
                        )
                    )) to "INVALID_RESPONSE",
                (200 to
                    wire(
                        testLibrary(reviews = listOf(testReview().copy(cardMutationId = "test-only-missing-card")))
                    )) to "INVALID_RESPONSE",
                (200 to wire(testLibrary(reviews = listOf(testReview().copy(grade = -1))))) to "INVALID_RESPONSE",
                (200 to wire(testLibrary(reviews = listOf(testReview().copy(grade = 6))))) to "INVALID_RESPONSE",
                library(repositories, sessionLimit = 0) to "INVALID_RESPONSE",
                library(repository(7, document(2).replace("false", "\"false\""))) to "INVALID_RESPONSE",
                library(repository(7, document(2)).replace("\"lastSyncedCommitSha\"", "\"unknownCheckpoint\"")) to
                    "INVALID_RESPONSE",
                library(
                    repository(7, document(2)),
                    readingStates = serverReading.replace("\"blockIndex\":0", "\"other\":0"),
                ) to "INVALID_RESPONSE",
                (200 to
                    JSONObject(library(repositories).second)
                        .apply {
                            getJSONObject("notebook")
                                .getJSONArray("annotations")
                                .put(JSONObject(serverHighlight("test-only-broken-card")).put("type", "CARD"))
                        }
                        .toString()) to "INVALID_RESPONSE",
            )) {
            LoopbackServer(mapOf("GET /api/library" to answer)).use { server ->
                try {
                    sync(server.url).syncLocalChanges()
                    fail("Expected $code to end the sync")
                } catch (error: ApiException) {
                    assertEquals(code, error.code)
                }
                assertEquals(listOf("GET /api/library"), server.requests)
            }
            assertEquals(code, before, saved())
            assertTrue(code, image.isFile)
        }
    }

    @Test
    fun aRepositoryTheServerAlreadyDisconnectedIsRemovedFromThePhoneInsteadOfFailing() = runBlocking {
        dao.replaceRepositories(listOf(RepositoryEntity(7, "o/notes", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", sha)))
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "a.md", "a", "<html/>", 0))
        LoopbackServer(emptyLibrary + mapOf("GET /api/repositories/7/stored-data" to failure(404, "NOT_FOUND"))).use {
            server ->
            assertNull(
                Sync(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, store, directory) {}
                    .disconnectPreview(7)
            )
            assertTrue(dao.repositoryIds().isEmpty())
            assertNull(dao.note(1))
        }
    }

    /** A test-only image of [documentId]'s snapshot [snapshotCommitSha], as the reader caches it. */
    private fun image(documentId: Long, snapshotCommitSha: String = sha) =
        File(directory, "images/$documentId/commit-$snapshotCommitSha/key").also {
            it.parentFile!!.mkdirs()
            it.writeText("test-only image")
        }

    /** A test-only image of [documentId] in a folder the app may not write, so it cannot be deleted until [after]. */
    private suspend fun <T> withStuckImage(documentId: Long, after: suspend (stuck: File) -> T): T {
        val stuck = image(documentId)
        check(stuck.parentFile!!.setWritable(false))
        return try {
            after(stuck)
        } finally {
            stuck.parentFile!!.setWritable(true)
        }
    }

    /** Repository 7 listing note 1, saved on the phone. */
    private suspend fun savedRepository() {
        dao.replaceRepositories(listOf(RepositoryEntity(7, "o/notes", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", sha)))
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "a.md", "a", "<html/>", 0, snapshotCommitSha = sha))
    }

    @Test
    fun aDisconnectRunAgainAfterItsAnswerWasLostDeletesImagesToo() = runBlocking {
        savedRepository()
        val image = image(1)
        LoopbackServer(
                mapOf(
                    "DELETE /api/repositories/7" to failure(404, "NOT_FOUND"),
                    "GET /api/library" to library(),
                )
            )
            .use { server ->
                val sync = sync(server.url)
                sync.disconnect(7)
                assertFalse(sync.saveReading(reading(1)))
                assertFalse(sync.setBookmark(1, true))
                assertNull(sync.createAnnotation(Passage(sha, "b1", 0, 4, "text"), 1, "late note"))
            }
        assertTrue(dao.repositoryIds().isEmpty())
        assertNull(dao.note(1))
        assertFalse(image.exists())
    }

    @Test
    fun imagesADisconnectCouldNotDeleteGoAtTheNextStart() = runBlocking {
        savedRepository()
        LoopbackServer(mapOf("DELETE /api/repositories/7" to (200 to wire(testDisconnected())))).use { server ->
            val sync = sync(server.url)
            withStuckImage(1) { stuck ->
                sync.disconnect(7)
                assertTrue(stuck.exists())
            }
            assertTrue(dao.repositoryIds().isEmpty())
            sync.startUp()
            assertFalse(File(directory, "images/1").exists())
        }
    }

    @Test
    fun startingDeletesEveryImageNoSavedPageShows() = runBlocking {
        savedRepository()
        val kept = image(1)
        val older = image(1, "b".repeat(40))
        val unsaved = image(2)
        sync("http://127.0.0.1:${closedPort()}").startUp()
        assertTrue(kept.exists())
        assertFalse(older.exists())
        assertFalse(unsaved.exists())
    }
}

/**
 * Test-only loopback HTTP server: answers each "METHOD /path" from [routes] (status and JSON body) and records every
 * request line and body. An unknown route is answered 500 and recorded, so it fails the test visibly. [beforeAnswer]
 * runs on the server thread after recording a request, so a test can hold one open.
 */
internal class LoopbackServer(
    private val routes: Map<String, Pair<Int, String>>,
    private val beforeAnswer: (String) -> Unit = {},
) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
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
        bodies += String(body)
        beforeAnswer(key)
        val (status, json) = routes[key] ?: (500 to """{"code":"TEST_UNEXPECTED_ROUTE","message":"$key"}""")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        try {
            output.write(
                "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            output.write(bytes)
            output.flush()
        } catch (gone: SocketException) {
            // The client stopped waiting (its read timeout) while beforeAnswer held the answer; it reads nothing more,
            // and later requests are still served.
            Log.i("RepoRead", "Test-only loopback answer not delivered; request=$key error=${gone.message}")
        }
    }

    override fun close() {
        socket.close()
        worker.join(5_000)
    }
}
