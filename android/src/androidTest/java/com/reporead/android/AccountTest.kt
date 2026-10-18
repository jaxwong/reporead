package com.reporead.android

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.reporead.android.sync.LocalCleanup
import com.reporead.android.sync.Sync
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun account(api: Api) = Account(sessions, api, Sync(api, store, directory) {}, scope)

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
                account.signOut()
                assertTrue(arrived.await(10, SECONDS))
                try {
                    account.signOut()
                    account.deleteAccount()
                    account.signIn("TEST_ONLY_CODE")
                } finally {
                    release.countDown()
                }
                assertEquals(AccountState.Idle(null), account.settled())
                assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
                assertPhoneCleared()
            }
    }

    /**
     * A reader released, a bookmark toggled, a grade or highlight saved while sign-out waits for the server lands
     * before the clear and is cleared; one arriving after it writes nothing.
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
                val account = Account(sessions, api, sync, scope)
                account.signOut()
                assertTrue(arrived.await(10, SECONDS))
                try {
                    assertTrue(sync.saveReading(reading().copy(lastReadAt = 2_000)))
                    assertTrue(sync.setBookmark(1, bookmarked = true))
                } finally {
                    release.countDown()
                }
                assertEquals(AccountState.Idle(null), account.settled())
                assertFalse(sync.saveReading(reading().copy(lastReadAt = 3_000)))
                assertFalse(sync.setBookmark(1, bookmarked = false))
                assertNull(sync.createAnnotation(Passage(sha, "b1", 0, 4, "text"), 1, null))
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
                Account(sessions, api(server.url), Sync(api(server.url), store, directory) {}, dying).deleteAccount()
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
}
