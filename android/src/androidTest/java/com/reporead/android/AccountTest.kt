// Owns: Isolated account-operation integration coverage; never the real user session, database or backend.

package com.reporead.android

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.StoredSession
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.AnnotationState
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.BookmarkEntity
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.LoopbackServer
import com.reporead.android.database.ReadingStateEntity
import com.reporead.android.database.RepoReadDao
import com.reporead.android.database.RepoReadDatabase
import com.reporead.android.database.RepositoryEntity
import com.reporead.android.database.ReviewEntity
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.database.SearchBlockEntity
import com.reporead.android.library.LibraryScreen
import com.reporead.android.network.Api
import com.reporead.android.network.testSession
import com.reporead.android.network.wire
import com.reporead.android.sync.PhoneSync
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sign-in, sign-out and account deletion through the production Account, SessionStore, Api, PhoneSync and Room wiring,
 * with test-only preferences, an in-memory database, a scratch folder and a loopback server; never the app's own
 * session, database or backend.
 */
@RunWith(AndroidJUnit4::class)
class AccountTest {
    private val sha = "a".repeat(40)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "test-only-session-${UUID.randomUUID()}"
    private lateinit var sessions: SessionStore
    private lateinit var store: RepoReadDatabase
    private lateinit var dao: RepoReadDao
    private lateinit var directory: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun open() {
        sessions = SessionStore(context, prefsName)
        store = Room.inMemoryDatabaseBuilder(context, RepoReadDatabase::class.java).build()
        dao = store.dao()
        directory = File(context.cacheDir, "test-only-account-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After
    fun close() {
        scope.cancel()
        store.close()
        context.deleteSharedPreferences(prefsName)
        check(directory.deleteRecursively())
    }

    private fun api(url: String) = Api(url, sessions::read, sessions::endRefused)

    private fun account(api: Api) = Account(sessions, api, scope)

    private fun account(url: String) = account(api(url))

    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    /** A port nothing listens on. */
    private fun closedPort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** Signed in as test-only user 5, with repository 7's note 1 saved, read, and a highlight not yet sent. */
    private suspend fun signedInWithSavedNotes() {
        sessions.save("TEST_ONLY_SESSION")
        dao.replaceRepositories(listOf(RepositoryEntity(7, "test-only/notes", 1, sha)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", sha)))
        dao.savePage(
            SavedPageEntity(1, sha, "a.md", "a", "<html/>", 0, snapshotCommitSha = sha),
            listOf(SearchBlockEntity(1, "b0", 0, "private text")),
        )
        assertTrue(dao.saveDisplayedReading(reading()))
        dao.saveAnnotation(
            AnnotationEntity("unsent", null, 1, sha, "b1", 0, 4, "text", null, 1, 0, state = AnnotationState.UNSENT)
        )
        dao.saveAnnotation(
            AnnotationEntity(
                "card",
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
                type = AnnotationType.CARD,
                question = "Test-only question?",
                state = AnnotationState.UNSENT,
            )
        )
        dao.saveReview(ReviewEntity("grade", "card", 4, 1_000, sha, pending = true))
        dao.saveBookmark(BookmarkEntity(1, true, true, 1_000))
        image()
    }

    private fun reading() =
        ReadingStateEntity(1, sha, 50, """{"headingPath":[],"textPrefix":null,"blockIndex":0}""", 1_000, pending = true)

    private fun image() =
        File(directory, "images/1/commit-$sha/key").also {
            it.parentFile!!.mkdirs()
            it.writeText("test-only image")
        }

    private suspend fun Account.settled(): AccountState.Idle =
        withTimeout(10_000) { state.first { it is AccountState.Idle } as AccountState.Idle }

    private suspend fun assertPhoneKept() {
        assertEquals("private text", dao.searchSavedPageText("%private%", 1).single().text)
        assertEquals(AnnotationState.UNSENT, dao.annotation("unsent")?.state)
        assertEquals(AnnotationState.UNSENT, dao.annotation("card")?.state)
        assertTrue(dao.reading(1)!!.pending)
        assertEquals(listOf("grade"), dao.pendingReviews().map { it.mutationId })
        assertEquals(listOf(1L), dao.pendingBookmarks().map { it.documentId })
        assertEquals("test-only image", File(directory, "images/1/commit-$sha/key").readText())
    }

    @Test
    fun signingOutKeepsThePhonesNotesAndPendingWork() = runBlocking {
        for (answer in listOf(204 to "", failure(503, "TEST_ONLY_DOWN"), failure(401, "UNAUTHENTICATED"))) {
            signedInWithSavedNotes()
            LoopbackServer(mapOf("DELETE /api/app-auth/session" to answer)).use { server ->
                val account = account(server.url)
                account.signOut()
                assertEquals(AccountState.Idle(null), account.settled())
                assertFalse(sessions.hasSession)
                assertPhoneKept()
                assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
            }
        }
        signedInWithSavedNotes()
        val unreachable = account("http://127.0.0.1:${closedPort()}")
        unreachable.signOut()
        assertEquals(AccountState.Idle(null), unreachable.settled())
        assertFalse(sessions.hasSession)
        assertPhoneKept()
    }

    @Test
    fun signingOutWithAnUnreadableSessionKeepsThePhonesCopy() = runBlocking {
        signedInWithSavedNotes()
        LoopbackServer(emptyMap()).use { server ->
            val unreadable =
                Api(server.url, { StoredSession.TemporarilyUnavailable(GeneralSecurityException("test-only")) }) {}
            val account = account(unreadable)
            account.signOut()
            assertEquals(AccountState.Idle(null), account.settled())
            assertFalse(sessions.hasSession)
            assertPhoneKept()
            assertEquals(emptyList<String>(), server.requests)
        }
    }

    @Test
    fun deletionKeepsThePhonesCopyInEveryOutcome() = runBlocking {
        val confirmed = 200 to """{"repositories":1,"readingStates":1,"bookmarks":1,"highlights":1,"cards":1}"""
        for (answer in
            listOf(
                confirmed,
                204 to "",
                200 to "{}",
                failure(503, "TEST_ONLY_DOWN"),
                failure(401, "UNAUTHENTICATED"),
            )) {
            signedInWithSavedNotes()
            LoopbackServer(mapOf("DELETE /api/account" to answer)).use { server ->
                val account = account(server.url)
                account.deleteAccount()
                val done = account.settled()
                if (answer == confirmed) assertEquals(AccountNotice.AccountDeleted, done.notice)
                else assertTrue(done.toString(), done.notice is AccountNotice.DeletionNotConfirmed)
                assertEquals(answer != confirmed && answer.first != 401, sessions.hasSession)
                assertPhoneKept()
                assertEquals(listOf("DELETE /api/account"), server.requests)
            }
        }
        signedInWithSavedNotes()
        val unreachable = account("http://127.0.0.1:${closedPort()}")
        unreachable.deleteAccount()
        assertTrue(unreachable.settled().notice is AccountNotice.DeletionNotConfirmed)
        assertTrue(sessions.hasSession)
        assertPhoneKept()
    }

    @Test
    fun signingInKeepsSavedDataIncludingWhenTheAccountChangesOrTheExchangeFails() = runBlocking {
        for (answer in
            listOf(
                200 to wire(testSession(accessToken = "TEST_ONLY_NEXT", userId = 6)),
                failure(400, "INVALID_SIGN_IN_CODE"),
            )) {
            signedInWithSavedNotes()
            sessions.clear()
            sessions.pendingVerifier = "test-only-verifier"
            LoopbackServer(mapOf("POST /api/app-auth/token" to answer)).use { server ->
                val account = account(server.url)
                account.signIn("TEST_ONLY_CODE")
                val done = account.settled()
                if (answer.first == 200) {
                    assertEquals(AccountState.Idle(null), done)
                    assertEquals(StoredSession.Readable("TEST_ONLY_NEXT"), sessions.read())
                } else {
                    assertTrue(done.notice is AccountNotice.SignInFailed)
                    assertFalse(sessions.hasSession)
                    account.dismissNotice()
                    assertEquals(AccountState.Idle(null), account.state.value)
                }
                assertNull(sessions.pendingVerifier)
                assertPhoneKept()
                assertEquals(listOf("POST /api/app-auth/token"), server.requests)
            }
        }
    }

    @Test
    fun anUnstartedSignInSendsNothingAndKeepsSavedData() = runBlocking {
        signedInWithSavedNotes()
        sessions.clear()
        LoopbackServer(emptyMap()).use { server ->
            val account = account(server.url)
            account.signIn("TEST_ONLY_UNSOLICITED_CODE")
            assertEquals("SIGN_IN_NOT_STARTED", (account.settled().notice as AccountNotice.SignInFailed).error.code)
            assertFalse(sessions.hasSession)
            assertPhoneKept()
            assertEquals(emptyList<String>(), server.requests)
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
                val sync = PhoneSync(api(server.url), store, directory) {}
                account.signOut()
                assertTrue(arrived.await(10, SECONDS))
                try {
                    assertEquals(AccountState.SigningOut, account.state.value)
                    account.signOut()
                    account.deleteAccount()
                    account.signIn("TEST_ONLY_CODE")
                    // Local work remains admitted while revocation waits and after it completes.
                    assertTrue(sync.saveReading(reading()))
                    assertPhoneKept()
                } finally {
                    release.countDown()
                }
                assertEquals(AccountState.Idle(null), account.settled())
                assertTrue(sync.saveReading(reading().copy(progressPercent = 70)))
                assertEquals(70, dao.reading(1)?.progressPercent)
                assertPhoneKept()
                assertEquals(listOf("DELETE /api/app-auth/session"), server.requests)
            }
    }

    @Test
    fun aDeletionFailureNoticeSurvivesLibraryRecreation(): Unit = runBlocking {
        signedInWithSavedNotes()
        dao.removeAnnotation("unsent")
        dao.saveAnnotation(checkNotNull(dao.annotation("card")).copy(state = AnnotationState.ACKNOWLEDGED))
        dao.saveReview(ReviewEntity("grade", "card", 4, 1_000, sha, pending = false))
        dao.saveBookmark(BookmarkEntity(1, true, false, 1_000))
        dao.saveReading(reading().copy(pending = false))
        val releaseRefresh = CountDownLatch(1)
        LoopbackServer(
                mapOf(
                    "DELETE /api/account" to failure(503, "TEST_ONLY_DOWN"),
                    "GET /api/library" to
                        (200 to
                            """{"repositories":[],"readingStates":[],"bookmarks":[],
                "notebook":{"annotations":[],"reviews":[]}}"""),
                )
            ) { key ->
                if (key == "GET /api/library") check(releaseRefresh.await(10, SECONDS))
            }
            .use { server ->
                val api = api(server.url)
                val sync = PhoneSync(api, store, directory) {}
                val account = Account(sessions, api, scope)
                account.deleteAccount()
                val notice = (account.settled() as AccountState.Idle).notice!!
                val text = noticeText(notice)
                TestHostActivity.content =
                    TestEntries(Screen.Repositories).content { entry ->
                        val state by account.state.collectAsState()
                        MaterialTheme {
                            LibraryScreen(
                                sync,
                                dao,
                                true,
                                entry.id,
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
                    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                        awaitText(text)
                        assertTrue(
                            (account.state.value as AccountState.Idle).notice is AccountNotice.DeletionNotConfirmed
                        )
                        scenario.recreate()
                        awaitText("Reading")
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
                    TestHostActivity.content = null
                }
            }
    }
}
