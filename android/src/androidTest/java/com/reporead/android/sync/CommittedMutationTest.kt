// Owns: Sync's committed boundary: a caller's cancellation (its screen left) never loses a confirmed or accepted
// change.

package com.reporead.android.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.TEST_CARD_MUTATION
import com.reporead.android.core.network.TEST_HIGHLIGHT_MUTATION
import com.reporead.android.core.network.testCard
import com.reporead.android.core.network.testCommit
import com.reporead.android.core.network.testDisconnected
import com.reporead.android.core.network.testHighlight
import com.reporead.android.core.network.testLibrary
import com.reporead.android.core.network.testPassage
import com.reporead.android.core.network.testRepository
import com.reporead.android.core.network.testSha
import com.reporead.android.core.network.testSynced
import com.reporead.android.core.network.wire
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.LocalStore
import com.reporead.android.data.LoopbackServer
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.RepositoryRow
import com.reporead.android.data.ReviewRow
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each test-only caller is cancelled as a left screen's ViewModel is, while its request or the lock holds it.
 * Production Sync/Api/Room against a loopback server and an in-memory database; every answer and row is a test fixture.
 */
@RunWith(AndroidJUnit4::class)
class CommittedMutationTest {
    private lateinit var store: LocalStore
    private lateinit var dao: LibraryDao
    private lateinit var directory: File
    /** Stands for a screen's viewModelScope: cancelling a job is that screen leaving. */
    private val screens = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun open() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        dao = store.library()
        directory = File(context.cacheDir, "test-only-committed-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        dao.replaceRepositories(listOf(RepositoryRow(7, "test-only/notes", 1, testSha)))
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "n.md", "Test-only note", testSha)))
        dao.saveNote(
            NoteRow(1, testSha, "n.md", "Test-only note", "<main id=note></main>", 0, renderFormat = RENDER_FORMAT)
        )
        dao.saveServerAnnotation(testHighlight().toRow())
        dao.saveServerAnnotation(testCard().toRow())
    }

    @After
    fun close() {
        screens.cancel()
        store.close()
        check(directory.deleteRecursively())
    }

    private fun sync(server: LoopbackServer) =
        Sync(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, store, directory) {}

    /**
     * Runs [operation] in a test-only screen job, cancels the job once [held] reaches the server, then lets the server
     * answer; returns every request made.
     */
    private fun leftWhileTheServerAnswers(
        routes: Map<String, Pair<Int, String>>,
        held: String,
        operation: suspend (Sync) -> Unit,
    ): List<String> = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(routes) { key ->
                if (key == held) {
                    arrived.countDown()
                    check(release.await(10, SECONDS)) { "Test never released $held" }
                }
            }
            .use { server ->
                val sync = sync(server)
                val screen = screens.launch { operation(sync) }
                try {
                    assertTrue("$held never reached the server", arrived.await(10, SECONDS))
                    screen.cancel()
                } finally {
                    release.countDown()
                }
                screen.join()
                assertTrue(screen.isCancelled)
                server.requests.toList()
            }
    }

    @Test
    fun aConfirmedDisconnectIsCommittedAfterItsScreenLeft() = runBlocking {
        val requests =
            leftWhileTheServerAnswers(
                mapOf("DELETE /api/repositories/7" to (200 to wire(testDisconnected(listOf(1))))),
                "DELETE /api/repositories/7",
            ) {
                it.disconnect(7)
            }
        assertEquals(listOf("DELETE /api/repositories/7"), requests)
        assertEquals(emptyList<RepositoryRow>(), dao.repositories().first())
        assertNull(dao.note(1))
    }

    @Test
    fun aRefreshFromGitHubStillReloadsTheLibraryAfterItsScreenLeft() = runBlocking {
        val refreshed = testRepository().copy(lastSyncedCommitSha = testCommit)
        val requests =
            leftWhileTheServerAnswers(
                mapOf(
                    "POST /api/repositories/7/sync" to (200 to wire(testSynced())),
                    "GET /api/library" to (200 to wire(testLibrary(repositories = listOf(refreshed)))),
                ),
                "POST /api/repositories/7/sync",
            ) {
                it.refreshFromGitHub(7)
            }
        assertEquals(listOf("POST /api/repositories/7/sync", "GET /api/library"), requests)
        assertEquals(testCommit, dao.repositories().first().single().lastSyncedCommitSha)
    }

    @Test
    fun anEditTheServerAcceptedIsSavedAfterItsScreenLeft() = runBlocking {
        val row = checkNotNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
        val edited = testHighlight().copy(note = "Test-only edited note", version = 2)
        val requests =
            leftWhileTheServerAnswers(
                mapOf("PATCH /api/annotations/9" to (200 to wire(edited))),
                "PATCH /api/annotations/9",
            ) {
                it.editAnnotation(row, "Test-only edited note", row.version)
            }
        assertEquals(listOf("PATCH /api/annotations/9"), requests)
        assertEquals("Test-only edited note", dao.annotation(TEST_HIGHLIGHT_MUTATION)?.note)
    }

    @Test
    fun aReattachTheServerAcceptedIsSavedAfterItsScreenLeft() = runBlocking {
        val row = checkNotNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
        val moved = testHighlight().copy(location = testPassage().copy(blockId = "b2"), version = 2)
        val requests =
            leftWhileTheServerAnswers(
                mapOf("POST /api/annotations/9/reattach" to (200 to wire(moved))),
                "POST /api/annotations/9/reattach",
            ) {
                it.reattachAnnotation(row, Passage(testSha, "b2", 0, 4, "text"))
            }
        assertEquals(listOf("POST /api/annotations/9/reattach"), requests)
        assertEquals(2, dao.annotation(TEST_HIGHLIGHT_MUTATION)?.version)
    }

    @Test
    fun aDeletionTheServerConfirmedIsRemovedAfterItsScreenLeft() = runBlocking {
        val row = checkNotNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
        val requests =
            leftWhileTheServerAnswers(
                mapOf("DELETE /api/annotations/9?expectedVersion=1" to (204 to "")),
                "DELETE /api/annotations/9?expectedVersion=1",
            ) {
                it.deleteAnnotation(row)
            }
        assertEquals(listOf("DELETE /api/annotations/9?expectedVersion=1"), requests)
        assertNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
    }

    @Test
    fun aCardTheServerConfirmedIsSavedAfterItsScreenLeft() = runBlocking {
        val row = checkNotNull(dao.annotation(TEST_CARD_MUTATION))
        val confirmed = testCard().copy(version = 2)
        val requests =
            leftWhileTheServerAnswers(
                mapOf("POST /api/annotations/10/check" to (200 to wire(confirmed))),
                "POST /api/annotations/10/check",
            ) {
                it.checkCard(row, testSha)
            }
        assertEquals(listOf("POST /api/annotations/10/check"), requests)
        assertEquals(2, dao.annotation(TEST_CARD_MUTATION)?.version)
    }

    /** The library the held load answers with: the same note and card, nothing else. */
    private val sameLibrary =
        "GET /api/library" to
            (200 to
                wire(
                    testLibrary(
                        repositories = listOf(testRepository()),
                        readingStates = emptyList(),
                        bookmarks = emptyList(),
                        annotations = listOf(testCard()),
                        reviews = emptyList(),
                    )
                ))

    private fun reading() =
        ReadingRow(1, testSha, 60, """{"headingPath":[],"textPrefix":null,"blockIndex":0}""", 2_000, pending = true)

    private fun grade() = ReviewRow("test-only-grade", TEST_CARD_MUTATION, 4, 2_000, testSha, pending = true)

    /** A library load, which holds the lock for as long as the server holds its answer. */
    private fun CoroutineScope.holdTheLock(sync: Sync) = async(Dispatchers.IO) { sync.refreshLibrary() }

    @Test
    fun localWritesWaitingForTheLockCommitOnceAfterTheirScreensLeft() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf(sameLibrary)) {
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the library load" }
            }
            .use { server ->
                val sync = sync(server)
                val load = holdTheLock(sync)
                val writes: List<Job>
                try {
                    assertTrue(arrived.await(10, SECONDS))
                    writes =
                        listOf(
                            screens.launch { sync.saveReading(reading()) },
                            screens.launch { sync.setBookmark(1, bookmarked = true) },
                            screens.launch { sync.createAnnotation(Passage(testSha, "b3", 0, 4, "text"), 1, null) },
                            screens.launch { sync.recordReview(grade()) },
                        )
                    // Each is now waiting for the lock the load holds.
                    delay(300)
                    writes.forEach { it.cancel() }
                } finally {
                    release.countDown()
                }
                load.await()
                writes.forEach { it.join() }
                assertTrue(writes.all { it.isCancelled })
                assertEquals(60, dao.reading(1)?.progressPercent)
                assertEquals(true, dao.bookmark(1).first()?.bookmarked)
                assertEquals(1, dao.annotations(1).first().count { it.state == AnnotationState.UNSENT })
                assertEquals(listOf("test-only-grade"), dao.pendingReviews().map { it.mutationId })
                assertEquals(listOf("GET /api/library"), server.requests)
            }
    }

    @Test
    fun aWriteWaitingForTheLockWhenTheCopyIsRemovedSavesNothing() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf(sameLibrary)) {
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the library load" }
            }
            .use { server ->
                val sync = sync(server)
                val load = holdTheLock(sync)
                val write = screens.async { sync.saveReading(reading()) }
                try {
                    assertTrue(arrived.await(10, SECONDS))
                    delay(300)
                    sync.retire()
                } finally {
                    release.countDown()
                }
                load.await()
                assertEquals(LocalCleanup.Done, sync.clearRetiredCopy())
                try {
                    write.await()
                    throw AssertionError("A retired local copy admitted a waiting write")
                } catch (expected: CancellationException) {
                    assertTrue(expected.message.orEmpty().contains("local copy was removed"))
                }
                assertNull(dao.reading(1))
                assertEquals(emptyList<RepositoryRow>(), dao.repositories().first())
            }
    }
}
