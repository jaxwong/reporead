package com.reporead.android

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.Api
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.LocalStore
import com.reporead.android.data.LoopbackServer
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.RepositoryRow
import com.reporead.android.library.LibraryScreen
import com.reporead.android.reader.ReaderTestActivity
import com.reporead.android.sync.LocalCleanup
import com.reporead.android.sync.Sync
import com.reporead.android.sync.saveAllInBackground
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sign-in, sign-out and account deletion through the production Account, SessionStore, Api, Sync and Room wiring, with
 * test-only preferences, an in-memory database, a scratch folder and a loopback server; never the app's own session,
 * database or backend. A second Account over the same preferences stands for the next process.
 */
@RunWith(AndroidJUnit4::class)
class AccountTest {
    private val sha = "a".repeat(40)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "test-only-session-${UUID.randomUUID()}"
    private lateinit var sessions: SessionStore
    private lateinit var store: LocalStore
    private lateinit var dao: LibraryDao
    private lateinit var directory: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** How many times an Account stopped Save all (its removal hook; WorkManager is the app's, never a test's). */
    private val saveAllForgotten = AtomicInteger()

    private suspend fun forgetSaveAll() {
        saveAllForgotten.incrementAndGet()
    }

    @Before
    fun open() {
        sessions = SessionStore(context, prefsName)
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        dao = store.library()
        directory = File(context.cacheDir, "test-only-account-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After
    fun close() {
        scope.cancel()
        store.close()
        context.deleteSharedPreferences(prefsName)
        directory.setWritable(true)
        directory.walkTopDown().forEach { it.setWritable(true) }
        check(directory.deleteRecursively())
    }

    private fun api(url: String) = Api(url, sessions::read, sessions::endRefused)

    private fun account(api: Api) = Account(sessions, api, Sync(api, store, directory) {}, scope, ::forgetSaveAll)

    private fun account(url: String) = account(api(url))

    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    /** A port nothing listens on. */
    private fun closedPort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** Signed in as test-only user 5, with repository 7's note 1 saved, read, and a highlight not yet sent. */
    private suspend fun signedInWithSavedNotes() {
        sessions.save("TEST_ONLY_SESSION")
        sessions.recordOwner(5)
        dao.replaceRepositories(listOf(RepositoryRow(7, "test-only/notes", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "a", sha)))
        dao.saveNote(NoteRow(1, sha, "a.md", "a", "<html/>", 0, "private text", snapshotCommitSha = sha))
        assertTrue(dao.saveDisplayedReading(reading()))
        dao.saveAnnotation(
            AnnotationRow("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        image()
    }

    private fun reading() =
        ReadingRow(1, sha, 50, """{"headingPath":[],"textPrefix":null,"blockIndex":0}""", 1_000, pending = true)

    private fun image() =
        File(directory, "images/1/commit-$sha/key").also {
            it.parentFile!!.mkdirs()
            it.writeText("test-only image")
        }

    private suspend fun Account.settled(): AccountState =
        withTimeout(10_000) {
            state.first { it is AccountState.Idle || (it is AccountState.Removing && it.stopped != null) }
        }

    private suspend fun assertPhoneCleared() {
        assertNull(dao.note(1))
        assertNull(dao.reading(1))
        assertEquals(0, dao.unsentCount())
        assertEquals(emptyList<File>(), directory.walkTopDown().filter { it.isFile }.toList())
        assertFalse(sessions.hasSession)
        assertNull(sessions.dataOwner)
        assertFalse(sessions.removalPending)
        assertTrue("A removal must stop Save all", saveAllForgotten.get() > 0)
    }

    private suspend fun assertPhoneKept() {
        assertEquals("private text", dao.note(1)?.searchText)
        // The unsent highlight and the pending reading position.
        assertEquals(AnnotationState.UNSENT, dao.annotation("unsent")?.state)
        assertTrue(dao.reading(1)!!.pending)
        assertEquals(2, dao.unsentCount())
        assertTrue(File(directory, "images/1/commit-$sha/key").isFile)
        assertEquals(5L, sessions.dataOwner)
        assertFalse(sessions.removalPending)
        assertEquals("Only a removal stops Save all", 0, saveAllForgotten.get())
    }

    @Test
    fun signingOutShowsTheRemovalAtOnceAndRemovesEverythingEvenWhenTheServerCannotRevoke() = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to failure(503, "TEST_ONLY_DOWN"))).use { server ->
            val account = account(server.url)
            account.signOut()
            // Ordinary screens close before the server answers, and process death from here still removes the copy.
            assertTrue(account.state.value is AccountState.Removing)
            assertTrue(sessions.removalPending)
            val done = account.settled()
            assertTrue(done.toString(), (done as AccountState.Idle).notice is AccountNotice.NotRevoked)
            assertPhoneCleared()
            assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
        }
    }

    @Test
    fun removalCannotPublishAFreshCopyUntilSaveAllCancellationCommits() = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))).use { server ->
            val cancelling = CompletableDeferred<Unit>()
            val committed = CompletableDeferred<Unit>()
            val api = api(server.url)
            val old = Sync(api, store, directory) {}
            val account =
                Account(sessions, api, old, scope) {
                    forgetSaveAll()
                    cancelling.complete(Unit)
                    committed.await()
                }
            account.startUp().join()
            account.signOut()
            withTimeout(10_000) { cancelling.await() }
            try {
                assertTrue(account.state.value is AccountState.Removing)
                assertTrue(sessions.removalPending)
                assertEquals(5L, sessions.dataOwner)
                assertFalse(sessions.hasSession)
                assertEquals("private text", dao.note(1)?.searchText)
                assertTrue(account.sync === old)
                assertRetired { old.saveReading(reading()) }
                assertEquals(Result.retry(), saveAllInBackground(account, 7, 0) {})
            } finally {
                committed.complete(Unit)
            }
            assertEquals(AccountState.Idle(null), account.settled())
            assertTrue(account.sync !== old)
            assertPhoneCleared()
        }
    }

    @Test
    fun aSaveAllDatabaseFailureKeepsRemovalPendingUntilCancellationCanBeRetried() = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))).use { server ->
            val failure = SQLiteException("TEST_ONLY_WORKMANAGER_WRITE_REFUSAL")
            val api = api(server.url)
            val old = Sync(api, store, directory) {}
            val account =
                Account(sessions, api, old, scope) {
                    forgetSaveAll()
                    if (saveAllForgotten.get() == 1) throw failure
                }
            account.startUp().join()
            account.signOut()
            val stopped = account.settled() as AccountState.Removing
            assertEquals(failure, (stopped.stopped as LocalCleanup.DatabaseFailed).error)
            assertTrue(sessions.removalPending)
            assertEquals(5L, sessions.dataOwner)
            assertFalse(sessions.hasSession)
            assertEquals("private text", dao.note(1)?.searchText)
            assertTrue(account.sync === old)
            account.signIn("TEST_ONLY_CODE")
            assertEquals(Result.retry(), saveAllInBackground(account, 7, 0) {})
            account.retryRemoval()
            assertEquals(AccountState.Idle(null), account.settled())
            assertEquals(2, saveAllForgotten.get())
            assertPhoneCleared()
            assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
        }
    }

    @Test
    fun aSecondTapOrAnotherOperationWhileOneRunsSendsNothing() = runBlocking {
        signedInWithSavedNotes()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))) {
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the held request" }
            }
            .use { server ->
                val account = account(server.url)
                // As the activity does at start, before any sign-out can be asked for.
                account.startUp().join()
                account.signOut()
                assertTrue(arrived.await(10, SECONDS))
                try {
                    account.signOut()
                    account.deleteAccount()
                    account.signIn("TEST_ONLY_CODE")
                    // Save all waits for the removal (which cancels it) without touching the copy.
                    assertEquals(Result.retry(), saveAllInBackground(account, 7, 0) {})
                } finally {
                    release.countDown()
                }
                assertEquals(AccountState.Idle(null), account.settled())
                assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
                assertPhoneCleared()
            }
    }

    /**
     * Retirement cancels new local work immediately, while revocation waits and after cleanup. The retired instance
     * never regains authority over a fresh local copy.
     */
    @Test
    fun localChangesDuringAndAfterTheRemovalLeaveNothingBehind() = runBlocking {
        signedInWithSavedNotes()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))) {
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the held request" }
            }
            .use { server ->
                val api = api(server.url)
                val sync = Sync(api, store, directory) {}
                val account = Account(sessions, api, sync, scope, ::forgetSaveAll)
                account.signOut()
                assertTrue(arrived.await(10, SECONDS))
                try {
                    assertRetired { sync.saveReading(reading().copy(lastReadAt = 2_000)) }
                    assertRetired { sync.setBookmark(1, bookmarked = true) }
                } finally {
                    release.countDown()
                }
                assertEquals(AccountState.Idle(null), account.settled())
                assertRetired { sync.saveReading(reading().copy(lastReadAt = 3_000)) }
                assertRetired { sync.setBookmark(1, bookmarked = false) }
                assertRetired { sync.createAnnotation(Passage(sha, "b1", 0, 4, "text"), 1, null) }
                assertPhoneCleared()
                assertEquals(emptyList<Any>(), dao.bookmarks().first())
            }
    }

    @Test
    fun aDeletionTheServerDoesNotConfirmKeepsEverythingOnThePhone() = runBlocking {
        for ((name, answer) in
            listOf("refused" to failure(503, "TEST_ONLY_DOWN"), "signed out" to failure(401, "UNAUTHENTICATED"))) {
            signedInWithSavedNotes()
            LoopbackServer(mapOf("DELETE /api/account" to answer)).use { server ->
                val account = account(server.url)
                account.deleteAccount()
                val done = account.settled()
                assertTrue("$name: $done", (done as AccountState.Idle).notice is AccountNotice.DeletionNotConfirmed)
                assertPhoneKept()
            }
        }
        // A 401 ends the session (the server refused it), but says nothing about the account: the copy stays.
        assertFalse(sessions.hasSession)
        sessions.save("TEST_ONLY_SESSION")
        val unreachable = account("http://127.0.0.1:${closedPort()}")
        unreachable.deleteAccount()
        val done = unreachable.settled()
        assertTrue(done.toString(), (done as AccountState.Idle).notice is AccountNotice.DeletionNotConfirmed)
        assertPhoneKept()
        assertTrue(sessions.hasSession)
    }

    @Test
    fun aConfirmedDeletionRemovesThePhonesCopy() = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(mapOf("DELETE /api/account" to (204 to ""))).use { server ->
            val account = account(server.url)
            account.deleteAccount()
            assertEquals(AccountState.DeletingAccount, account.state.value)
            assertEquals(AccountState.Idle(AccountNotice.AccountDeleted), account.settled())
            assertPhoneCleared()
        }
    }

    /** The process dies while the deletion waits for its answer: unknown on the server, so the next start keeps it. */
    @Test
    fun aDeletionCutShortBeforeItsAnswerKeepsEverythingAtTheNextStart() = runBlocking {
        signedInWithSavedNotes()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("DELETE /api/account" to (204 to ""))) {
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the held request" }
            }
            .use { server ->
                val dying = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                Account(sessions, api(server.url), Sync(api(server.url), store, directory) {}, dying, ::forgetSaveAll)
                    .deleteAccount()
                assertTrue(arrived.await(10, SECONDS))
                dying.cancel()
                val next = account(server.url)
                try {
                    assertEquals(AccountState.Idle(null), next.state.value)
                    next.startUp()
                    assertPhoneKept()
                } finally {
                    release.countDown()
                }
            }
    }

    /** The process dies after sign-out was decided: the next start finishes the removal before any screen. */
    @Test
    fun aRemovalCutShortIsFinishedAtTheNextStart() = runBlocking {
        signedInWithSavedNotes()
        sessions.startRemoval()
        val next = account("http://127.0.0.1:${closedPort()}")
        assertEquals(AccountState.Removing(null, null), next.state.value)
        next.startUp()
        assertEquals(AccountState.Idle(null), next.settled())
        assertPhoneCleared()
    }

    @Test
    fun aFileThatWillNotDeleteStopsTheRemovalAndSignInWaitsUntilRetryFinishesIt() = runBlocking {
        signedInWithSavedNotes()
        val stuck = File(directory, "images/1/commit-$sha")
        check(stuck.setWritable(false))
        LoopbackServer(
                mapOf(
                    "DELETE /api/app-auth/session" to (204 to ""),
                    "POST /api/app-auth/token" to
                        (200 to """{"accessToken":"TEST_ONLY_NEXT","user":{"id":6,"login":"test-only"}}"""),
                )
            )
            .use { server ->
                val account = account(server.url)
                account.signOut()
                val stopped = account.settled() as AccountState.Removing
                assertTrue(stopped.toString(), stopped.stopped is LocalCleanup.FilesLeft)
                assertTrue(sessions.removalPending)
                assertEquals(5L, sessions.dataOwner)
                assertNull(dao.note(1))
                sessions.pendingVerifier = "test-only-verifier"
                account.signIn("TEST_ONLY_CODE")
                assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
                check(stuck.setWritable(true))
                account.retryRemoval()
                assertEquals(AccountState.Idle(null), account.settled())
                assertPhoneCleared()
            }
    }

    @Test
    fun signingOutWithAnUnreadableSessionStillRemovesThePhonesCopy() = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(emptyMap()).use { server ->
            val unreadable =
                Api(server.url, { StoredSession.TemporarilyUnavailable(GeneralSecurityException("test-only")) }) {}
            val account = account(unreadable)
            account.signOut()
            val done = account.settled() as AccountState.Idle
            assertEquals("SESSION_UNREADABLE", (done.notice as AccountNotice.NotRevoked).error.code)
            assertPhoneCleared()
            assertEquals(emptyList<String>(), server.requests)
        }
    }

    /** Data saved before owners were recorded, whose session has ended: no session is issued over it. */
    @Test
    fun signingInOverDataWithNoRecordedOwnerRemovesItFirst() = runBlocking {
        signedInWithSavedNotes()
        sessions.clear()
        sessions.finishRemoval()
        assertNull(sessions.dataOwner)
        LoopbackServer(
                mapOf(
                    "POST /api/app-auth/token" to
                        (200 to """{"accessToken":"TEST_ONLY_NEXT","user":{"id":6,"login":"test-only"}}""")
                )
            )
            .use { server ->
                val account = account(server.url)
                sessions.pendingVerifier = "test-only-verifier"
                account.signIn("TEST_ONLY_CODE")
                assertEquals(AccountState.Idle(null), account.settled())
                assertNull(dao.note(1))
                assertEquals(0, dao.unsentCount())
                assertEquals(6L, sessions.dataOwner)
                assertEquals(StoredSession.Readable("TEST_ONLY_NEXT"), sessions.read())
                assertFalse(sessions.removalPending)
                assertEquals(listOf("POST /api/app-auth/token"), server.requests)
            }
    }

    @Test
    fun signingInWithARecordedOwnerKeepsItsDataAndAFailureIsReported() = runBlocking {
        signedInWithSavedNotes()
        sessions.clear()
        LoopbackServer(mapOf("POST /api/app-auth/token" to failure(409, "ACCOUNT_MISMATCH"))).use { server ->
            val account = account(server.url)
            sessions.pendingVerifier = "test-only-verifier"
            account.signIn("TEST_ONLY_CODE")
            val done = account.settled() as AccountState.Idle
            assertEquals("ACCOUNT_MISMATCH", (done.notice as AccountNotice.SignInFailed).error.code)
            account.dismissNotice()
            assertEquals(AccountState.Idle(null), account.state.value)
            assertPhoneKept()
        }
    }

    @Test
    fun anUnstartedSignInCannotDeleteLegacyData(): Unit = runBlocking {
        signedInWithSavedNotes()
        sessions.clear()
        sessions.finishRemoval() // Same legacy ownerless fixture as the PR's existing upgrade test.
        assertNull(sessions.pendingVerifier)
        LoopbackServer(emptyMap()).use { server ->
            val account = account(server.url)
            account.signIn("TEST_ONLY_UNSOLICITED_CODE")
            val done = account.settled() as AccountState.Idle
            assertEquals("SIGN_IN_NOT_STARTED", (done.notice as AccountNotice.SignInFailed).error.code)
            assertEquals(emptyList<String>(), server.requests)
            assertEquals(
                "An unstarted sign-in callback erased the saved legacy page",
                "private text",
                dao.note(1)?.searchText,
            )
        }
    }

    @Test
    fun aDeletionFailureNoticeSurvivesLibraryRecreation(): Unit = runBlocking {
        signedInWithSavedNotes()
        dao.removeAnnotation("unsent")
        dao.saveReading(reading().copy(pending = false))
        val releaseRefresh = CountDownLatch(1)
        LoopbackServer(
                mapOf(
                    "DELETE /api/account" to failure(503, "TEST_ONLY_DOWN"),
                    "GET /api/library" to
                        (200 to
                            """{"repositories":[],"readingStates":[],"bookmarks":[],
                "notebook":{"annotations":[],"reviews":[],"sessionLimit":40}}"""),
                )
            ) { key ->
                if (key == "GET /api/library") check(releaseRefresh.await(10, SECONDS))
            }
            .use { server ->
                val api = api(server.url)
                val sync = Sync(api, store, directory) {}
                val account = Account(sessions, api, sync, scope, ::forgetSaveAll)
                account.deleteAccount()
                val notice = (account.settled() as AccountState.Idle).notice!!
                val text = noticeText(notice)
                ReaderTestActivity.content = {
                    val state by account.state.collectAsState()
                    MaterialTheme {
                        LibraryScreen(
                            sync,
                            dao,
                            true,
                            {},
                            (state as? AccountState.Idle)?.notice?.let(::noticeText),
                            account::dismissNotice,
                            {},
                            account::signOut,
                            account::deleteAccount,
                        )
                    }
                }
                try {
                    ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                        awaitText(text)
                        assertTrue(
                            (account.state.value as AccountState.Idle).notice is AccountNotice.DeletionNotConfirmed
                        )
                        scenario.recreate()
                        awaitText("Library")
                        assertNotNull(
                            "Account-deletion failure notice disappeared on recreation",
                            findText(root(), text),
                        )
                        releaseRefresh.countDown()
                        // The refresh may replace the library, but not the separately owned account notice.
                        withTimeout(5_000) { dao.repositories().first { it.isEmpty() } }
                        awaitText(text)
                        click("Dismiss account message")
                        assertEquals(AccountState.Idle(null), account.state.value)
                    }
                } finally {
                    releaseRefresh.countDown()
                    ReaderTestActivity.content = null
                }
            }
    }

    @Test
    fun aPreRemovalWriteCannotSurviveSigningBackIntoTheSameAccount(): Unit = runBlocking {
        signedInWithSavedNotes()
        val library =
            """{"repositories":[{"id":7,"fullName":"test-only/notes","documentCount":1,
            "lastSyncedCommitSha":"$sha","documents":[{"id":1,"path":"a.md","title":"a","blobSha":"$sha",
            "contentChangedAt":null,"deleted":false}]}],"readingStates":[],"bookmarks":[],
            "notebook":{"annotations":[],"reviews":[],"sessionLimit":40}}"""
        LoopbackServer(
                mapOf(
                    "DELETE /api/app-auth/session" to (204 to ""),
                    "POST /api/app-auth/token" to (200 to """{"accessToken":"TEST_ONLY_NEXT","user":{"id":5}}"""),
                    "GET /api/library" to (200 to library),
                )
            )
            .use { server ->
                val api = api(server.url)
                val sync = Sync(api, store, directory) {}
                val account = Account(sessions, api, sync, scope, ::forgetSaveAll)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val oldReading = reading().copy(progressPercent = 90, lastReadAt = 2_000)
                val oldWrite = scope.async {
                    started.complete(Unit)
                    release.await()
                    sync.saveReading(oldReading)
                }
                started.await()
                try {
                    account.signOut()
                    assertEquals(AccountState.Idle(null), account.settled())
                    assertPhoneCleared()
                    sessions.pendingVerifier = "test-only-verifier"
                    account.signIn("TEST_ONLY_CODE")
                    assertEquals(AccountState.Idle(null), account.settled())
                    val fresh = account.sync
                    assertTrue("Removal must publish a different Sync lifetime", fresh !== sync)
                    fresh.refreshLibrary()
                    assertTrue(fresh.saveReading(reading().copy(progressPercent = 10, lastReadAt = 3_000)))
                    release.complete(Unit)
                    assertRetired { oldWrite.await() }
                    assertTrue(oldWrite.isCancelled)
                    assertEquals(10, dao.reading(1)?.progressPercent)
                } finally {
                    release.complete(Unit)
                }
            }
    }

    @Test
    fun backgroundStartCannotUploadWorkAfterRemovalWasPersisted(): Unit = runBlocking {
        signedInWithSavedNotes()
        dao.removeAnnotation("unsent")
        sessions.startRemoval() // Process stopped here: the original token and unsent reading still exist.
        LoopbackServer(mapOf("PUT /api/documents/1/reading-state" to (200 to "{}"))).use { server ->
            val sync = Sync(api(server.url), store, directory) {}
            // The actual worker entry must finish the persisted removal before considering pending changes.
            com.reporead.android.sync.backgroundSync(
                Account(sessions, api(server.url), sync, scope, ::forgetSaveAll),
                0,
            )
            assertEquals("Pending removal was bypassed by background sync", emptyList<String>(), server.requests)
            assertPhoneCleared()
        }
    }

    @Test
    fun anExportAlreadyWritingIsRemovedAndQueuedExportsCannotPublish(): Unit = runBlocking {
        signedInWithSavedNotes()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))).use { server ->
            val api = api(server.url)
            val old = Sync(api, store, directory) {}
            val account = Account(sessions, api, old, scope, ::forgetSaveAll)
            account.startUp().join()
            val exporting = scope.async {
                old.exportNotebook {
                    writing.countDown()
                    check(release.await(10, SECONDS)) { "Test never released the admitted export" }
                    "# Test-only private notebook"
                }
            }
            assertTrue(writing.await(10, SECONDS))
            val queued = scope.async {
                old.exportNotebook { error("A queued retired export must not format or write") }
            }
            try {
                account.signOut()
                assertTrue(account.state.value is AccountState.Removing)
                // Removal cannot finish while the admitted exporter owns the publication lock.
                assertTrue(sessions.removalPending)
                assertEquals(5L, sessions.dataOwner)
            } finally {
                release.countDown()
            }
            val file = exporting.await()
            assertEquals(AccountState.Idle(null), account.settled())
            assertFalse(file.exists())
            assertRetired { queued.await() }
            assertRetired { old.exportNotebook { error("A retired export must not write") } }
            assertRetired { old.openNote(1) }
            assertRetired { old.refreshLibrary() }
            assertRetired { old.changesSeen(1, sha) }
            assertRetired { old.dismissRefusedReviews() }
            assertNull(old.images.image(1, sha, "a.png"))
            assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
            assertPhoneCleared()
        }
    }

    @Test
    fun theRealRootGateSurvivesRotationWhileRemovalWaits(): Unit = runBlocking {
        signedInWithSavedNotes()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))) {
                arrived.countDown()
                check(release.await(10, SECONDS))
            }
            .use { server ->
                val api = api(server.url)
                val account = Account(sessions, api, Sync(api, store, directory) {}, scope, ::forgetSaveAll)
                account.startUp().join()
                account.signOut()
                assertTrue(arrived.await(10, SECONDS))
                ReaderTestActivity.content = {
                    MaterialTheme { RepoReadApp(account, sessions, api, dao, scope, null, {}) }
                }
                try {
                    ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                        awaitText("Removing this phone's copy of your notes…")
                        assertNull(findText(root(), "Library"))
                        scenario.recreate()
                        awaitText("Removing this phone's copy of your notes…")
                        assertNull(findText(root(), "Library"))
                        release.countDown()
                        assertEquals(AccountState.Idle(null), account.settled())
                        awaitText("Sign in with GitHub")
                    }
                    assertPhoneCleared()
                } finally {
                    release.countDown()
                    ReaderTestActivity.content = null
                }
            }
    }

    @Test
    fun aRealDatabaseWriteRefusalKeepsRemovalStoppedUntilRetry(): Unit = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(mapOf("DELETE /api/app-auth/session" to (204 to ""))).use { server ->
            val account = account(server.url)
            store.openHelper.writableDatabase.execSQL("PRAGMA query_only = ON")
            try {
                account.signOut()
                val stopped = account.settled() as AccountState.Removing
                assertTrue(stopped.toString(), stopped.stopped is LocalCleanup.DatabaseFailed)
                assertTrue(sessions.removalPending)
                assertEquals(5L, sessions.dataOwner)
                assertNotNull(dao.note(1))
                account.signIn("TEST_ONLY_CODE")
                assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
            } finally {
                store.openHelper.writableDatabase.execSQL("PRAGMA query_only = OFF")
            }
            account.retryRemoval()
            assertEquals(AccountState.Idle(null), account.settled())
            assertPhoneCleared()
        }
    }
}

/** A retired copy cancels old work; catching here asserts cancellation without cancelling the test's own job. */
internal suspend fun assertRetired(work: suspend () -> Unit) {
    try {
        work()
        throw AssertionError("A retired local copy admitted an operation")
    } catch (expected: CancellationException) {
        assertTrue(expected.message.orEmpty().contains("local copy was removed"))
    }
}
