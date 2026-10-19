// Owns: The real RepoReadApp's back stack: each entry's saved UI state, the account screens over the stack, recreation
// and process restoration. Isolated test-only data; never the app's own session, database or backend.

package com.reporead.android

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.auth.SessionStore
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.testCounts
import com.reporead.android.core.network.testDisconnected
import com.reporead.android.core.network.testLibrary
import com.reporead.android.core.network.testRepository
import com.reporead.android.core.network.testSession
import com.reporead.android.core.network.testSha
import com.reporead.android.core.network.wire
import com.reporead.android.core.network.wire.DocumentsSummary
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.LocalStore
import com.reporead.android.data.LoopbackServer
import com.reporead.android.data.NoteRow
import com.reporead.android.data.RepositoryRow
import com.reporead.android.reader.testPage
import com.reporead.android.sync.RENDER_FORMAT
import com.reporead.android.sync.Sync
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Drives the real RepoReadApp with the production Account, Sync and Room wiring over test-only preferences, an
 * in-memory database, a scratch folder and a loopback server. Repository 7 has notes A and B at its root, which link to
 * each other, and C in the folder "deep", which links to A: A's "Linked from" lists B and C, B's lists A, so the open
 * panel tells the two readers apart.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefsName = "test-only-session-${UUID.randomUUID()}"
    private lateinit var sessions: SessionStore
    private lateinit var store: LocalStore
    private lateinit var dao: LibraryDao
    private lateinit var directory: File
    private var scenario: ActivityScenario<TestHostActivity>? = null
    /** Like RepoReadApplication.scope, it outlives the activity. */
    private val scope = MainScope()
    /** The code of a sign-in redirect, as MainActivity hands it to the app. */
    private val signInCode = mutableStateOf<String?>(null)

    private data class Note(val id: Long, val path: String, val title: String, val linksTo: String)

    private val notes =
        listOf(Note(1, "a.md", "Note A", "b"), Note(2, "b.md", "Note B", "a"), Note(3, "deep/c.md", "Note C", "a"))

    @Before
    fun open() = runBlocking {
        sessions = SessionStore(context, prefsName)
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        dao = store.library()
        directory = File(context.cacheDir, "test-only-navigation-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        sessions.recordOwner(5)
        dao.replaceRepositories(listOf(RepositoryRow(7, "test-only/notes", notes.size, testSha)))
        dao.replaceDocuments(7, notes.map { DocumentRow(it.id, 7, it.path, it.title, testSha) })
        for (note in notes) {
            val body =
                """<p data-block-id="b0" data-anchor-text="Test-only text">Test-only text, then """ +
                    """<a href="/note-link?target=${note.linksTo}">onward</a>.</p>"""
            dao.saveNote(
                NoteRow(
                    note.id,
                    testSha,
                    note.path,
                    note.title,
                    testPage(testSha, body),
                    0,
                    renderFormat = RENDER_FORMAT,
                    snapshotCommitSha = testSha,
                )
            )
        }
    }

    @After
    fun close() {
        scenario?.close()
        instrumentation.waitForIdleSync()
        TestHostActivity.content = null
        TestHostActivity.restoredState = null
        scope.cancel()
        store.close()
        context.deleteSharedPreferences(prefsName)
        check(directory.deleteRecursively())
    }

    /**
     * What the server lists: while [connected], the same three notes at the same versions as this phone's copies;
     * otherwise nothing.
     */
    private fun library(connected: Boolean = true) =
        200 to
            wire(
                testLibrary(
                    repositories =
                        if (!connected) emptyList()
                        else
                            listOf(
                                testRepository(
                                    notes.map { DocumentsSummary(testSha, null, false, it.id, it.path, it.title) }
                                )
                            ),
                    readingStates = emptyList(),
                    bookmarks = emptyList(),
                    annotations = emptyList(),
                    reviews = emptyList(),
                )
            )

    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    private fun api(server: LoopbackServer) = Api(server.url, sessions::read, sessions::endRefused)

    private fun account(api: Api) =
        Account(sessions, api, Sync(api, store, directory) {}, scope) {}.also { runBlocking { it.startUp().join() } }

    private fun launch(content: @Composable () -> Unit) {
        TestHostActivity.content = content
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
    }

    private fun launchApp(account: Account, api: Api) = launch {
        RepoReadApp(account, sessions, api, dao, scope, signInCode.value) { signInCode.value = null }
    }

    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    /** Whether the Library's [tab] is the selected one; a selected tab is not clickable, so its node is read as is. */
    private fun selected(tab: String): Boolean =
        generateSequence(awaitText(tab)) { it.parent }.any(AccessibilityNodeInfo::isSelected)

    /** Waits until every text in [shown] is on screen and none in [hidden] is. */
    private fun awaitScreen(vararg shown: String, hidden: List<String> = emptyList()) = runBlocking {
        fun missing() = shown.filter { findText(root(), it) == null }
        fun extra() = hidden.filter { findText(root(), it) != null }
        try {
            withTimeout(10_000) { while (missing().isNotEmpty() || extra().isNotEmpty()) delay(50) }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Screen still lacks ${missing()} and shows ${extra()}", error)
        }
    }

    // What each reader shows; its title is the only text of a closed panel, and the open panel lists Linked from.
    private fun awaitNoteA(panelOpen: Boolean) =
        if (panelOpen) awaitScreen("Note A", "Linked from", "Note B", "Note C")
        else awaitScreen("Note A", hidden = listOf("Linked from", "Note B", "Note C"))

    private fun awaitNoteB(panelOpen: Boolean) =
        if (panelOpen) awaitScreen("Note B", "Linked from", "Note A", hidden = listOf("Note C"))
        else awaitScreen("Note B", hidden = listOf("Linked from", "Note A", "Note C"))

    private fun awaitFolder() =
        awaitScreen("test-only/notes", "deep", "Note A", "Note B", hidden = listOf("Linked from"))

    /** Library → Repositories → the repository → note A with its panel open. */
    private fun openNoteAWithItsPanel() {
        awaitText("Library")
        click("Repositories")
        click("test-only/notes")
        awaitFolder()
        click("Note A")
        awaitNoteA(panelOpen = false)
        click("Highlights and notes")
        awaitNoteA(panelOpen = true)
    }

    /** Presses Back and waits for it to finish the activity: nothing was left below the screen shown. */
    private fun awaitBackLeavesTheApp() = runBlocking {
        pressBack()
        withTimeout(10_000) { while (scenario!!.state != Lifecycle.State.DESTROYED) delay(50) }
    }

    /**
     * Waits until the folder's own refresh has finished: until then its other actions start nothing, and Refresh from
     * GitHub, disabled meanwhile, shows when it is done.
     */
    private fun awaitFolderRefreshed(server: LoopbackServer) = runBlocking {
        withTimeout(10_000) {
            // The library on entering, then the folder's own refresh.
            while (server.requests.count { it == "GET /api/library" } < 2) delay(50)
            while (findText(root(), "Refresh from GitHub")?.isEnabled != true) delay(50)
        }
    }

    /**
     * Runs the shown screen's Back twice in one turn of the main thread, as two taps that both land before the screen
     * recomposes: the second reaches the same, already removed, screen.
     */
    private fun tapBackTwiceBeforeRecomposition() {
        fun View.composeRoot(): ViewRootForTest? =
            this as? ViewRootForTest
                ?: (this as? ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).composeRoot() }
                }
        scenario!!.onActivity { activity ->
            val back =
                checkNotNull(activity.window.decorView.composeRoot()).semanticsOwner.getAllSemanticsNodes(true).single {
                    "Back" in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                }
            val onBack = checkNotNull(back.config[SemanticsActions.OnClick].action)
            onBack()
            onBack()
        }
        instrumentation.waitForIdleSync()
    }

    /** Waits for in-flight screen work to finish, then checks nothing more was sent. */
    private fun assertNothingMoreSent(server: LoopbackServer, before: List<String>) = runBlocking {
        delay(1_000)
        assertEquals(before, server.requests.toList())
    }

    @Test
    fun equalNotesInOneStackKeepTheirOwnPanelsThroughRecreationAndBack() {
        LoopbackServer(emptyMap()).use { server ->
            val api = api(server)
            launchApp(account(api), api)
            openNoteAWithItsPanel()
            click("Note B")
            awaitNoteB(panelOpen = false)
            click("Highlights and notes")
            awaitNoteB(panelOpen = true)
            // The same note again: a new entry, with its own panel closed.
            click("Note A")
            awaitNoteA(panelOpen = false)
            scenario!!.recreate()
            awaitNoteA(panelOpen = false)
            pressBack()
            awaitNoteB(panelOpen = true)
            pressBack()
            awaitNoteA(panelOpen = true)
            pressBack()
            awaitFolder()
            pressBack()
            awaitText("test-only/notes")
            assertTrue(selected("Repositories"))
            assertEquals(emptyList<String>(), server.requests)
        }
    }

    @Test
    fun aRestoredProcessReopensTheSameStackWithEachEntrysState() {
        LoopbackServer(emptyMap()).use { server ->
            val api = api(server)
            val account = account(api)
            launchApp(account, api)
            openNoteAWithItsPanel()
            val saved = saveActivityState(scenario!!)
            scenario!!.close()
            scenario = null
            TestHostActivity.restoredState = saved
            launchApp(account, api)
            awaitNoteA(panelOpen = true)
            pressBack()
            awaitFolder()
            pressBack()
            awaitText("test-only/notes")
            assertTrue(selected("Repositories"))
            assertEquals(emptyList<String>(), server.requests)
        }
    }

    @Test
    fun aLegacyActivityStateStartsAtTheRootWithoutChangingSavedData() = runBlocking {
        dao.saveAnnotation(
            AnnotationRow(
                "test-only-pending",
                null,
                1,
                testSha,
                "b0",
                0,
                4,
                "Test",
                null,
                1,
                0,
                state = AnnotationState.UNSENT,
            )
        )
        LoopbackServer(emptyMap()).use { server ->
            val api = api(server)
            val account = account(api)
            val file = File(directory, "test-only-preserved.txt").apply { writeText("Test-only saved file") }
            TestHostActivity.restoredState = legacyNavigationState()
            launchApp(account, api)
            awaitScreen("Library", hidden = listOf("Note A", "Linked from", "Test-only legacy stack"))
            assertTrue(selected("Reading"))
            assertEquals(3, dao.documents(7).first().size)
            assertNotNull(dao.note(1))
            assertEquals(AnnotationState.UNSENT, dao.annotation("test-only-pending")?.state)
            assertEquals("Test-only saved file", file.readText())
            assertEquals(emptyList<String>(), server.requests)
            awaitBackLeavesTheApp()
        }
    }

    @Test
    fun aFailedSignInReturnsToTheSameEntriesWithTheirState() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        LoopbackServer(mapOf("POST /api/app-auth/token" to failure(400, "TEST_ONLY_REFUSED"))) {
                arrived.countDown()
                check(release.await(10, SECONDS))
            }
            .use { server ->
                val api = api(server)
                launchApp(account(api), api)
                try {
                    openNoteAWithItsPanel()
                    sessions.pendingVerifier = "test-only-verifier"
                    scenario!!.onActivity { signInCode.value = "TEST_ONLY_CODE" }
                    assertTrue(arrived.await(10, SECONDS))
                    awaitScreen("Finishing sign-in…", hidden = listOf("Note A", "Linked from"))
                } finally {
                    release.countDown()
                }
                click("Back to saved notes")
                awaitNoteA(panelOpen = true)
                pressBack()
                awaitFolder()
                pressBack()
                awaitText("test-only/notes")
                assertTrue(selected("Repositories"))
                assertEquals(listOf("POST /api/app-auth/token"), server.requests)
            }
    }

    @Test
    fun aFailedAccountDeletionReturnsToTheSameLibrary() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions.save("TEST_ONLY_SESSION")
        LoopbackServer(
                mapOf("GET /api/library" to library(), "DELETE /api/account" to failure(503, "TEST_ONLY_DOWN"))
            ) { key ->
                if (key == "DELETE /api/account") {
                    arrived.countDown()
                    check(release.await(10, SECONDS))
                }
            }
            .use { server ->
                val api = api(server)
                launchApp(account(api), api)
                try {
                    awaitText("Library")
                    click("Repositories")
                    awaitText("test-only/notes")
                    click("More")
                    click("Delete account…")
                    click("Delete account")
                    assertTrue(arrived.await(10, SECONDS))
                    awaitScreen("Deleting your RepoRead account…", hidden = listOf("Library"))
                } finally {
                    release.countDown()
                }
                awaitTextStarting("Couldn't confirm the account deletion")
                awaitScreen("Library", "test-only/notes")
                assertTrue(selected("Repositories"))
                assertTrue(sessions.hasSession)
            }
    }

    @Test
    fun signingInOverOwnerlessDataRetiresTheOldStack() = runBlocking {
        // Test-only legacy copy: saved notes exist, but no account owns them (AccountTest's upgrade fixture).
        sessions.finishRemoval()
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(connected = false),
                    "POST /api/app-auth/token" to (200 to wire(testSession(userId = 6))),
                )
            )
            .use { server ->
                val api = api(server)
                val account = account(api)
                val oldCopy = account.sync
                launchApp(account, api)
                openNoteAWithItsPanel()
                sessions.pendingVerifier = "test-only-verifier"
                scenario!!.onActivity { signInCode.value = "TEST_ONLY_CODE" }
                awaitScreen("Library", hidden = listOf("Note A", "Linked from"))
                assertTrue(selected("Reading"))
                assertTrue(account.sync !== oldCopy)
                assertNull(dao.note(1))
                assertEquals(6L, sessions.dataOwner)
                awaitBackLeavesTheApp()
                assertEquals(listOf("POST /api/app-auth/token", "GET /api/library"), server.requests)
            }
    }

    @Test
    fun aRefusedOwnerlessSignInDoesNotRestoreTheRetiredStackOnTheSecondAttempt() = runBlocking {
        sessions.finishRemoval()
        val routes =
            ConcurrentHashMap(
                mapOf(
                    "GET /api/library" to library(connected = false),
                    "POST /api/app-auth/token" to failure(400, "TEST_ONLY_REFUSED"),
                )
            )
        LoopbackServer(routes).use { server ->
            val api = api(server)
            launchApp(account(api), api)
            openNoteAWithItsPanel()
            sessions.pendingVerifier = "test-only-verifier"
            scenario!!.onActivity { signInCode.value = "TEST_ONLY_FIRST_CODE" }
            awaitScreen("Sign in with GitHub", hidden = listOf("Note A", "Linked from"))
            awaitText("Test-only TEST_ONLY_REFUSED")
            assertNull(dao.note(1))
            assertEquals(listOf("POST /api/app-auth/token"), server.requests)
            routes["POST /api/app-auth/token"] = 200 to wire(testSession(userId = 6))
            sessions.pendingVerifier = "test-only-next-verifier"
            scenario!!.onActivity { signInCode.value = "TEST_ONLY_SECOND_CODE" }
            awaitScreen("Library", hidden = listOf("Note A", "Linked from"))
            assertTrue(selected("Reading"))
            awaitBackLeavesTheApp()
            assertEquals(
                listOf("POST /api/app-auth/token", "POST /api/app-auth/token", "GET /api/library"),
                server.requests,
            )
        }
    }

    /** Sign-out is offered only at the root, so the old state left to clear is the root's own (its tab). */
    @Test
    fun signingOutStartsTheNextAccountAtAFreshRoot() {
        sessions.save("TEST_ONLY_SESSION")
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(),
                    "DELETE /api/app-auth/session" to (204 to ""),
                    "POST /api/app-auth/token" to (200 to wire(testSession(userId = 5))),
                )
            )
            .use { server ->
                val api = api(server)
                launchApp(account(api), api)
                awaitText("Library")
                click("Repositories")
                awaitText("test-only/notes")
                click("More")
                click("Sign out and remove from phone…")
                click("Sign out")
                awaitScreen("Sign in with GitHub", hidden = listOf("Library"))
                sessions.pendingVerifier = "test-only-verifier"
                scenario!!.onActivity { signInCode.value = "TEST_ONLY_CODE" }
                awaitText("Library")
                assertTrue(selected("Reading"))
                assertFalse(selected("Repositories"))
            }
    }

    @Test
    fun signOutConfirmationCountsWhatIsLostAndCancelChangesNothing() = runBlocking {
        sessions.save("TEST_ONLY_SESSION")
        dao.saveAnnotation(
            AnnotationRow("unsent", null, 1, testSha, "b0", 0, 4, "Test", null, 1, 0, state = AnnotationState.UNSENT)
        )
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(),
                    "POST /api/documents/1/annotations" to failure(503, "TEST_ONLY_DOWN"),
                )
            )
            .use { server ->
                val api = api(server)
                launchApp(account(api), api)
                awaitTextStarting("New highlights and cards stay on this phone for now.")
                click("More")
                click("Sign out and remove from phone…")
                awaitMatch("the unsent count") {
                    it.contains("1 change on this phone never reached the server and will be lost.")
                }
                val before = server.requests.toList()
                click("Cancel")
                awaitScreen("Library", hidden = listOf("Sign out of RepoRead?"))
                assertNothingMoreSent(server, before)
                assertEquals(AnnotationState.UNSENT, dao.annotation("unsent")?.state)
                assertNotNull(dao.note(1))
                assertTrue(sessions.hasSession)
            }
    }

    @Test
    fun deleteAccountConfirmationNamesTheRepositoriesAndCancelSendsNothing() {
        sessions.save("TEST_ONLY_SESSION")
        LoopbackServer(mapOf("GET /api/library" to library())).use { server ->
            val api = api(server)
            launchApp(account(api), api)
            awaitText("Library")
            runBlocking { withTimeout(10_000) { while ("GET /api/library" !in server.requests) delay(50) } }
            click("More")
            click("Delete account…")
            awaitMatch("the repositories") { it.contains("your connections (test-only/notes)") }
            val before = server.requests.toList()
            click("Cancel")
            awaitScreen("Library", hidden = listOf("Delete your RepoRead account?"))
            assertNothingMoreSent(server, before)
            assertTrue(sessions.hasSession)
        }
    }

    @Test
    fun disconnectConfirmationShowsTheServersCountsAndCancelSendsNothing() = runBlocking {
        sessions.save("TEST_ONLY_SESSION")
        LoopbackServer(
                mapOf(
                    "GET /api/library" to library(),
                    "GET /api/repositories/7/stored-data" to (200 to wire(testCounts())),
                )
            )
            .use { server ->
                val api = api(server)
                launchApp(account(api), api)
                awaitText("Library")
                click("Repositories")
                click("test-only/notes")
                awaitFolder()
                awaitFolderRefreshed(server)
                click("More")
                click("Disconnect…")
                awaitMatch("the server's counts") {
                    it.contains(
                        "its list of 1 note, your reading progress on 1 note, 1 bookmark, 1 highlight with their notes, " +
                            "and 1 card with their review logs."
                    )
                }
                val before = server.requests.toList()
                click("Cancel")
                awaitScreen("deep", hidden = listOf("Disconnect test-only/notes?"))
                assertNothingMoreSent(server, before)
                assertEquals(3, dao.documents(7).first().size)
                assertEquals(listOf("test-only/notes"), dao.repositories().first().map { it.fullName })
            }
    }

    /** A screen's Back leaves that screen only, however often it is tapped before the next screen shows. */
    @Test
    fun aRepeatedBackTapLeavesOnlyItsOwnScreen() {
        LoopbackServer(emptyMap()).use { server ->
            val api = api(server)
            launchApp(account(api), api)
            awaitText("Library")
            click("Repositories")
            click("test-only/notes")
            awaitFolder()
            click("Note A")
            awaitNoteA(panelOpen = false)
            tapBackTwiceBeforeRecomposition()
            awaitFolder()
            pressBack()
            awaitText("test-only/notes")
            assertTrue(selected("Repositories"))
            assertEquals(emptyList<String>(), server.requests)
        }
    }

    /** A confirmed disconnect returns to the Library, in the state it was left, with nothing left above or below it. */
    @Test
    fun aConfirmedDisconnectReturnsToTheLibrary() = runBlocking {
        sessions.save("TEST_ONLY_SESSION")
        val routes =
            ConcurrentHashMap(
                mapOf(
                    "GET /api/library" to library(),
                    "GET /api/repositories/7/stored-data" to (200 to wire(testCounts())),
                    "DELETE /api/repositories/7" to (200 to wire(testDisconnected(notes.map { it.id }))),
                )
            )
        // Once disconnected, the server no longer lists the repository.
        LoopbackServer(routes) { key ->
                if (key == "DELETE /api/repositories/7") routes["GET /api/library"] = library(connected = false)
            }
            .use { server ->
                val api = api(server)
                launchApp(account(api), api)
                awaitText("Library")
                click("Repositories")
                click("test-only/notes")
                awaitFolder()
                awaitFolderRefreshed(server)
                click("More")
                click("Disconnect…")
                awaitText("Disconnect test-only/notes?")
                click("Disconnect")
                awaitScreen("Library", hidden = listOf("test-only/notes", "Disconnect test-only/notes?"))
                assertTrue(selected("Repositories"))
                assertEquals(emptyList<RepositoryRow>(), dao.repositories().first())
                awaitBackLeavesTheApp()
            }
    }
}
