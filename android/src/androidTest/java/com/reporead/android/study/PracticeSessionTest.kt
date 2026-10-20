package com.reporead.android.study

import android.graphics.Rect
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.BuildConfig
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.TestEntries
import com.reporead.android.TestHostActivity
import com.reporead.android.awaitText
import com.reporead.android.click
import com.reporead.android.control
import com.reporead.android.core.network.Api
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.findText
import com.reporead.android.findWebView
import com.reporead.android.pageBounds
import com.reporead.android.reader.testPage
import com.reporead.android.root
import com.reporead.android.saveActivityState
import com.reporead.android.sync.Sync
import com.reporead.android.tap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Practice session UI, answer pages and Room writes; test-only rows, zero network calls. */
@RunWith(AndroidJUnit4::class)
class PracticeSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val bankSha = "a".repeat(40)
    private val coreSha = "c".repeat(40)
    private lateinit var store: LocalStore
    private lateinit var files: File

    private fun block(id: String, tag: String, text: String) =
        """<$tag data-block-id="$id" data-anchor-text="$text">$text</$tag>"""

    private fun answer(id: String, text: String, heading: String) =
        """<p data-block-id="$id" data-anchor-text="Canonical answer: $text">""" +
            """<strong>Canonical answer:</strong> <a href="/note-link?path=test-only%2Ftopic%2Fcore.md&amp;heading=$heading">$text</a></p>"""

    /** A wide table in the saved answer section. */
    private val wideTable =
        "<table><tr>" + (1..20).joinToString("") { "<td>test-only-wide-column-$it</td>" } + "</tr></table>"
    /** What the session asked the app to open. */
    private val pushed = mutableListOf<Screen>()

    @Before
    fun open() = runBlocking {
        val context = instrumentation.targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        files = File(context.cacheDir, "practice-session-test")
        check(files.mkdirs() || files.isDirectory) { "Cannot create test-only directory $files" }
        val dao = store.library()
        // Both questions read the same; each must take its own answer line and section.
        val bank =
            testPage(
                bankSha,
                block("b0", "h1", "Test-only bank") +
                    block("b1", "h2", "1. Foundations") +
                    block("b2", "h3", "1. An identical question?") +
                    answer("b3", "First answer", "first-answer") +
                    block("b4", "h3", "2. An identical question?") +
                    answer("b5", "Second answer", "second-answer"),
            )
        val core =
            testPage(
                coreSha,
                block("c0", "h1", "Test-only core") +
                    block("c1", "p", "Test-only introduction.") +
                    block("c2", "h2", "First answer") +
                    block("c3", "p", "Test-only first answer text.") +
                    wideTable +
                    block("c4", "h2", "Second answer") +
                    block("c5", "p", "Test-only second answer text.") +
                    wideTable,
            )
        dao.replaceDocuments(
            7,
            listOf(
                DocumentRow(1, 7, "test-only/topic/interview/bank.md", "Test-only bank", bankSha),
                DocumentRow(2, 7, "test-only/topic/core.md", "Test-only core", coreSha),
            ),
        )
        dao.saveNote(
            NoteRow(
                1,
                bankSha,
                "test-only/topic/interview/bank.md",
                "Test-only bank",
                bank,
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
            )
        )
        dao.saveNote(
            NoteRow(
                2,
                coreSha,
                "test-only/topic/core.md",
                "Test-only core",
                core,
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
                snapshotCommitSha = "d".repeat(40),
            )
        )
        val sync =
            Sync(Api("http://127.0.0.1:1", { error("Practice fixture must not call the network") }) {}, store, files) {}
        TestHostActivity.content =
            TestEntries(Screen.Practice("test-only/topic")).content { entry ->
                MaterialTheme {
                    PracticeScreen(dao, sync, "test-only/topic", entry.id, push = { pushed += it }, onBack = {})
                }
            }
    }

    @After
    fun close() {
        instrumentation.waitForIdleSync()
        TestHostActivity.content = null
        if (::store.isInitialized) store.close()
        if (::files.isInitialized) check(files.deleteRecursively()) { "Cannot delete test-only directory $files" }
    }

    @Test
    fun aWarningOpensItsOwnBankWhenRepositoriesShareThePath() =
        runBlocking<Unit> {
            val dao = store.library()
            val path = "test-only/topic/interview/bank.md"
            dao.replaceDocuments(8, listOf(DocumentRow(3, 8, path, "Other bank", bankSha)))
            val html = testPage(bankSha, block("b0", "h3", "Other repository's warning"))
            dao.saveNote(NoteRow(3, bankSha, path, "Other bank", html, 0, renderFormat = BuildConfig.RENDER_FORMAT))
            TestHostActivity.content = { MaterialTheme { PracticeTab(dao) { pushed += it } } }
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("1 heading is not a question")
                click("Show")
                awaitText("Other repository's warning")
                scenario.recreate()
                awaitText("Other repository's warning")
                click("Other repository's warning")
                assertEquals(listOf(Screen.Reader(3, "Other bank", OpenAt.Block(bankSha, "b0"))), pushed)
            }
        }

    @Test
    fun repeatedHeadingAndStrayAnswerWarningsOpenTheirExactBlocks() =
        runBlocking<Unit> {
            val dao = store.library()
            val path = "test-only/topic/interview/bank.md"
            dao.replaceDocuments(8, listOf(DocumentRow(3, 8, path, "Other bank", bankSha)))
            val html =
                testPage(
                    bankSha,
                    block("b0", "p", "Canonical answer: Stray") +
                        block("b1", "h3", "Repeated") +
                        answer("b2", "First answer", "first-answer") +
                        block("b3", "h3", "Repeated"),
                )
            dao.saveNote(NoteRow(3, bankSha, path, "Other bank", html, 0, renderFormat = BuildConfig.RENDER_FORMAT))
            TestHostActivity.content = { MaterialTheme { PracticeTab(dao) { pushed += it } } }
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("2 headings are not questions")
                assertNull(findText(root(), "Repeated"))
                click("Show")
                click("Canonical answer: Stray")
                click("Repeated")
                assertEquals(
                    listOf(
                        Screen.Reader(3, "Other bank", OpenAt.Block(bankSha, "b0")),
                        Screen.Reader(3, "Other bank", OpenAt.Block(bankSha, "b3")),
                    ),
                    pushed,
                )
                click("Hide")
                awaitText("Show")
                assertNull(findText(root(), "Repeated"))
            }
        }

    @Test
    fun eachQuestionTakesItsOwnAnswerAMissComesBackUnsavedAndTheSessionSurvivesRecreation() =
        runBlocking<Unit> {
            val dao = store.library()
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("2 questions left · Foundations · Test-only bank")
                val prepared = preparedAnswer(scenario)
                val firstBounds = pageBounds(scenario)
                click("Show answer")
                val first = shownAnswer(scenario)
                assertSame("Reveal uses the section prepared during the attempt", prepared, findWebView(scenario))
                assertEquals("Reveal keeps the prepared viewport", firstBounds, pageBounds(scenario))
                click("Again")
                val missed =
                    withTimeout(5_000) {
                        while (dao.waitingAnnotations().isEmpty() || dao.pendingReviews().isEmpty()) delay(20)
                        dao.waitingAnnotations().single()
                    }
                assertEquals(AnnotationType.CARD, missed.type)
                assertEquals("An identical question?", missed.question)
                assertEquals("Canonical answer: $first", missed.exactText)
                assertEquals(if (first == "First answer") "b3" else "b5", missed.blockId)
                assertEquals(bankSha, missed.checkedBlobSha)
                assertEquals(listOf(0), dao.pendingReviews().map { it.grade })

                // The miss went to the back; the other question is next, also after recreation. The grade is saved
                // before the queue moves on, and the count reads the same either way, so recreating only once the next
                // question is up (Show answer again) saves the moved queue.
                awaitText("Show answer")
                scenario.recreate()
                awaitText("2 questions left · Foundations · Test-only bank")
                preparedAnswer(scenario)
                val secondBounds = pageBounds(scenario)
                click("Show answer")
                val second = shownAnswer(scenario)
                assertEquals("The next answer also keeps its prepared viewport", secondBounds, pageBounds(scenario))
                assertTrue("The other question comes next", second != first)
                click("Good")
                withTimeout(5_000) { while (dao.pendingReviews().size != 2) delay(20) }

                // The miss comes back; grading it again decides only whether it returns.
                awaitText("1 question left · Foundations · Test-only bank")
                preparedAnswer(scenario)
                click("Show answer")
                assertEquals(first, shownAnswer(scenario))
                awaitText(
                    "Asked again: its first grade is saved. This grade only decides whether it comes back in this session."
                )
                click("Good")
                awaitText("Session complete: 2 questions graded. They come back under Review when due.")
                assertEquals(2, dao.waitingAnnotations().size)
                assertEquals(
                    setOf("Canonical answer: First answer", "Canonical answer: Second answer"),
                    dao.waitingAnnotations().map { it.exactText }.toSet(),
                )
                assertEquals(listOf(0, 4), dao.reviews().first().map { it.grade })
            }
            assertEquals(emptyList<Screen>(), pushed)
            // A new session asks only questions without a card.
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText(
                    "No new questions here: each question in its up-to-date banks already has a card. They come back under Review when due."
                )
            }
        }

    /** T15: a fresh process restores the session where it stood, with a new owner and nothing graded again. */
    @Test
    fun aRestoredProcessResumesTheSessionWithoutGradingAgain() =
        runBlocking<Unit> {
            val dao = store.library()
            val saved =
                ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                    awaitText("2 questions left · Foundations · Test-only bank")
                    click("Show answer")
                    click("Good")
                    withTimeout(5_000) { while (dao.pendingReviews().size != 1) delay(20) }
                    awaitText("1 question left · Foundations · Test-only bank")
                    click("Show answer")
                    awaitText("Good")
                    saveActivityState(scenario)
                }
            TestHostActivity.restoredState = saved
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("1 question left · Foundations · Test-only bank")
                awaitText("Good")
                assertEquals(1, dao.pendingReviews().size)
            }
        }

    @Test
    fun doubleTappingTheGradeWritesOneCardAndOneGrade() =
        runBlocking<Unit> {
            val dao = store.library()
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                click("Show answer")
                val bounds = Rect().also { control("Good").getBoundsInScreen(it) }
                // Accessibility clicks wait for idle and can target a replaced node, missing this same-frame race.
                scenario.onActivity { activity ->
                    repeat(2) { tap(activity.window.decorView, bounds) }
                }
                awaitText("1 question left · Foundations · Test-only bank")
                val card = dao.waitingAnnotations().single()
                assertEquals(listOf(card.mutationId), dao.pendingReviews().map { it.cardMutationId })
                assertEquals(listOf(4), dao.pendingReviews().map { it.grade })
            }
        }

    @Test
    fun thePreparedNativePageRejectsHiddenInputAndAcceptsInputAfterReveal() =
        runBlocking<Unit> {
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("Show answer")
                val page = preparedAnswer(scenario)
                evaluate(
                    scenario,
                    "window.testTouches = 0; document.addEventListener('touchstart', () => window.testTouches++); return {};",
                )
                val bounds = pageBounds(scenario)
                scenario.onActivity {
                    assertTrue("The hidden native page rejects focus", !page.requestFocus())
                    tap(it.window.decorView, bounds)
                }
                instrumentation.waitForIdleSync()
                assertEquals(0, evaluate(scenario, "return {touches: window.testTouches};").getInt("touches"))
                click("Show answer")
                shownAnswer(scenario)
                scenario.onActivity {
                    assertTrue("The visible native page accepts focus", page.requestFocus())
                    tap(it.window.decorView, bounds)
                }
                withTimeout(5_000) {
                    while (evaluate(scenario, "return {touches: window.testTouches};").getInt("touches") == 0) delay(20)
                }
                assertEquals(1, evaluate(scenario, "return {touches: window.testTouches};").getInt("touches"))
            }
        }

    @Test
    fun aFailedPrefetchedPageReportsItsFailureOnlyAfterReveal() =
        runBlocking<Unit> {
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("Show answer")
                val prepared = preparedAnswer(scenario)
                scenario.onActivity { prepared.loadUrl("chrome://crash") }
                withTimeout(10_000) { while (findWebView(scenario) != null) delay(50) }
                val problem = "Test-only core could not be shown."
                assertNull("A failed prefetch must not reveal the note's title", findText(root(), problem))
                click("Show answer")
                awaitText(problem)
                awaitText("Good")
                assertNull(findWebView(scenario))
            }
        }

    @Test
    fun anEarlyRevealNeverShowsAnAnswerBeforeItsSectionFinishesPreparing() =
        runBlocking<Unit> {
            val dao = store.library()
            val core = checkNotNull(dao.note(2))
            // Test-only page with no renderer: preparation stays pending until the explicit failure below.
            dao.saveNote(core.copy(html = core.html.replace("<script defer src=\"/assets/reader.js\"></script>", "")))
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                repeat(2) {
                    awaitText("Show answer")
                    click("Show answer")
                    try {
                        assertNull(
                            "A reveal must not expose only the Canonical line over an unprepared section",
                            findText(root()) { it.startsWith("Canonical answer:") },
                        )
                        assertNull("Grades must arrive with the complete answer", findText(root(), "Good"))
                        awaitText("Preparing answer…")
                    } finally {
                        withTimeout(5_000) { while (findWebView(scenario) == null) delay(20) }
                        evaluate(scenario, "document.body.dataset.state = 'failed'; return {};")
                        awaitText("Test-only core could not be shown.")
                    }
                    awaitText("Good")
                    click("Good")
                }
                awaitText("Session complete: 2 questions graded. They come back under Review when due.")
            }
        }

    @Test
    fun theGuideAndLinkChoicesStayHiddenAndAnotherSectionCanStillBeSelected() =
        runBlocking<Unit> {
            val dao = store.library()
            val note = checkNotNull(dao.note(1))
            val guide = "Test-only grading checklist"
            val html =
                testPage(
                    bankSha,
                    block("b0", "h3", "Test-only multi-link question?") +
                        block("b1", "p", guide) +
                        """<p data-block-id="b2" data-anchor-text="Canonical answer: First answer and Second answer"><strong>Canonical answer:</strong> <a href="/note-link?path=test-only%2Ftopic%2Fcore.md&amp;heading=first-answer">First answer</a> and <a href="/note-link?path=test-only%2Ftopic%2Fcore.md&amp;heading=second-answer">Second answer</a></p>""",
                )
            dao.saveNote(note.copy(html = html))
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("Show answer")
                preparedAnswer(scenario)
                for (text in listOf(guide, "1. First answer", "2. Second answer")) assertNull(
                    "No guide or link choices before reveal",
                    findText(root(), text),
                )
                val bounds = pageBounds(scenario)
                click("Show answer")
                awaitText(guide)
                assertEquals("First answer", shownAnswer(scenario))
                assertEquals(
                    "The guide and link choices already occupy their final space",
                    bounds,
                    pageBounds(scenario),
                )
                click("2. Second answer")
                awaitText("Test-only core # Second answer")
                assertEquals("Second answer", shownAnswer(scenario))
            }
        }

    @Test
    fun aMissingLinkedPageIsReportedOnlyAfterReveal() =
        runBlocking<Unit> {
            store.library().deleteNotes(listOf(2))
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Show answer")
                val problem =
                    "Not saved on this phone yet, so this section can't be shown here. Open the note to fetch it."
                assertNull(findText(root(), problem))
                assertNull(findText(root(), "Open note"))
                click("Show answer")
                awaitText(problem)
                awaitText("Open note")
                awaitText("Good")
            }
        }

    @Test
    fun aGradeWaitingForSyncSurvivesPoppingItsEntry() = runBlocking {
        val dao = store.library()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(true)
        // A real local write holds Sync's lock at its notification boundary, never a second DAO implementation.
        val sync =
            Sync(Api("http://127.0.0.1:1", { error("Practice test must not call the network") }) {}, store, files) {
                if (hold.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test never released the local write" }
                }
            }
        val entries = TestEntries(Screen.Practice("test-only/topic"))
        TestHostActivity.content = entries.content { entry ->
            MaterialTheme {
                if (entry.screen is Screen.Practice) PracticeScreen(dao, sync, "test-only/topic", entry.id, {}, {})
                else Text("Test-only popped Practice")
            }
        }
        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            awaitText("Show answer")
            click("Show answer")
            awaitText("Good")
            val holding = async(Dispatchers.IO) { sync.createAnnotation(Passage(bankSha, "b0", 0, 4, "Test"), 1, null) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                click("Good")
                scenario.onActivity { entries.replaceTop(Screen.Search, "test-only-after-practice") }
                awaitText("Test-only popped Practice")
            } finally {
                release.countDown()
            }
            holding.await()
            withTimeout(5_000) { while (dao.pendingReviews().isEmpty()) delay(20) }
            val card = dao.waitingAnnotations().single { it.type == AnnotationType.CARD }
            assertEquals(listOf(card.mutationId), dao.pendingReviews().map { it.cardMutationId })
        }
    }

    /** Preparation must finish without exposing any answer text or focus target. */
    private suspend fun preparedAnswer(scenario: ActivityScenario<TestHostActivity>): WebView {
        val view =
            withTimeout(10_000) {
                while (true) {
                    val page = findWebView(scenario)
                    if (
                        page != null &&
                            evaluate(
                                    scenario,
                                    "return {ready: document.body?.dataset.state === 'ready' && document.querySelector('[data-block-id=\"c1\"]').getBoundingClientRect().height === 0};",
                                )
                                .optBoolean("ready")
                    )
                        return@withTimeout page
                    delay(50)
                }
                error("Unreachable")
            }
        scenario.onActivity {
            assertEquals(0f, view.alpha)
            assertTrue("The hidden answer has a laid-out slot", view.height > 0)
            assertNull("No virtual accessibility tree before reveal", view.accessibilityNodeProvider)
            assertTrue("The hidden page cannot take keyboard focus", !view.requestFocus())
        }
        for (text in
            listOf(
                "Canonical answer: First answer",
                "Canonical answer: Second answer",
                "Linked section · saved copy",
                "Open note",
                "Good",
                "Again",
                "Test-only first answer text.",
                "Test-only second answer text.",
            )) assertNull("No answer in accessibility before reveal: $text", findText(root(), text))
        return view
    }

    /** Which linked section the revealed answer shows; it must be that section alone, from the saved copy. */
    private fun shownAnswer(scenario: ActivityScenario<TestHostActivity>): String = runBlocking {
        awaitText("Your grade confirms only this Canonical line. The linked section is supplementary.")
        awaitText("Linked section · saved copy")
        val label =
            withTimeout(5_000) {
                while (true) {
                    listOf("First answer", "Second answer")
                        .firstOrNull { findText(root(), "Test-only core # $it") != null }
                        ?.let {
                            return@withTimeout it
                        }
                    delay(50)
                }
                error("Unreachable")
            }
        val (own, other) = if (label == "First answer") "c3" to "c5" else "c5" to "c3"
        var last = JSONObject()
        repeat(100) {
            // The page turns visible only after the rest is hidden, so read it after seeing it visible, never before.
            val visible = findWebView(scenario)?.alpha == 1f
            last =
                evaluate(
                    scenario,
                    """
                if (!document.body || document.body.dataset.state !== 'ready') return {};
                const shown = id => document.querySelector('[data-block-id="' + id + '"]').getBoundingClientRect().height > 0;
                return {own: shown('$own'), other: shown('$other'), intro: shown('c1')};
            """
                        .trimIndent(),
                )
            if (visible && last.optBoolean("own")) {
                assertTrue(
                    "Only the linked section shows: $last",
                    !last.getBoolean("other") && !last.getBoolean("intro"),
                )
                awaitText("Test-only ${if (label == "First answer") "first" else "second"} answer text.")
                return@runBlocking label
            }
            delay(100)
        }
        throw AssertionError("Answer section $label never showed; last=$last")
    }

    private fun evaluate(scenario: ActivityScenario<TestHostActivity>, script: String): JSONObject {
        val done = CountDownLatch(1)
        var encoded: String? = "\"{}\""
        scenario.onActivity { activity ->
            val view = findWebView(activity.window.decorView)
            if (view == null) done.countDown()
            else
                view.evaluateJavascript(
                    "JSON.stringify((() => { try { $script } catch (error) { return {error: error.stack}; } })())"
                ) {
                    encoded = it
                    done.countDown()
                }
        }
        assertTrue("Answer page JavaScript timed out", done.await(5, TimeUnit.SECONDS))
        val result = JSONObject(JSONTokener(encoded).nextValue() as String)
        check(!result.has("error")) { "Answer page JavaScript failed: $result" }
        return result
    }
}
