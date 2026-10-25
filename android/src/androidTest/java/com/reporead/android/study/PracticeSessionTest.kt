package com.reporead.android.study

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
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.Passage
import com.reporead.android.database.RepoReadDatabase
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.database.saveNoteWithoutSearch
import com.reporead.android.findText
import com.reporead.android.network.Api
import com.reporead.android.reader.testPage
import com.reporead.android.root
import com.reporead.android.saveActivityState
import com.reporead.android.sync.PhoneSync
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Practice session UI and Room writes; test-only rows, zero network calls. */
@RunWith(AndroidJUnit4::class)
class PracticeSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val bankSha = "a".repeat(40)
    private val coreSha = "c".repeat(40)
    private lateinit var store: RepoReadDatabase
    private lateinit var files: File

    private fun block(id: String, tag: String, text: String) =
        """<$tag data-block-id="$id" data-anchor-text="$text">$text</$tag>"""

    private fun labelled(id: String, label: String, text: String = "") =
        """<p data-block-id="$id" data-anchor-text="$label $text"><strong>$label</strong> $text</p>"""

    private fun link(text: String, heading: String) =
        """<a href="/note-link?path=test-only%2Ftopic%2Fcore.md&amp;heading=$heading">$text</a>"""

    private fun answer(id: String, text: String, heading: String) =
        """<p data-block-id="$id" data-anchor-text="Canonical answer: $text"><strong>Canonical answer:</strong> ${link(text, heading)}</p>"""

    /**
     * Question [n] in the bank contract's shape: heading q[n], then [prompt] material, the labels with an optional
     * [worked] answer, and its Canonical [line].
     */
    private fun question(
        n: Int,
        heading: String,
        short: String,
        line: String,
        prompt: String = "",
        worked: String = "",
    ) =
        block("q$n", "h3", heading) +
            prompt +
            labelled("t$n", "Interviewer is testing:", "Test-only recall.") +
            labelled("c$n", "A complete answer should:") +
            "<ul>" +
            block("i$n", "li", "Test-only checklist point") +
            "</ul>" +
            worked +
            labelled("s$n", "Short answer:", short) +
            line

    private val firstShort = "Test-only first short answer."
    private val secondShort = "Test-only second short answer."

    /** What Practice may show only after Show answer. */
    private val answerTexts =
        listOf(firstShort, secondShort, "Test-only checklist point", "First answer", "Second answer", "Good", "Again")

    /** What the session asked the app to open. */
    private val pushed = mutableListOf<Screen>()

    @Before
    fun open() = runBlocking {
        val context = instrumentation.targetContext
        store = Room.inMemoryDatabaseBuilder(context, RepoReadDatabase::class.java).build()
        files = File(context.cacheDir, "practice-session-test")
        check(files.mkdirs() || files.isDirectory) { "Cannot create test-only directory $files" }
        val dao = store.dao()
        // Both questions read the same; each must take its own answer.
        val bank =
            testPage(
                bankSha,
                block("b0", "h1", "Test-only bank") +
                    block("b1", "h2", "1. Foundations") +
                    question(1, "1. An identical question?", firstShort, answer("a1", "First answer", "first-answer")) +
                    question(
                        2,
                        "2. An identical question?",
                        secondShort,
                        answer("a2", "Second answer", "second-answer"),
                    ),
            )
        val core =
            testPage(
                coreSha,
                block("c0", "h1", "Test-only core") +
                    block("c2", "h2", "First answer") +
                    block("c3", "p", "Test-only first answer text.") +
                    block("c4", "h2", "Second answer") +
                    block("c5", "p", "Test-only second answer text."),
            )
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "test-only/topic/interview/bank.md", "Test-only bank", bankSha),
                DocumentEntity(2, 7, "test-only/topic/core.md", "Test-only core", coreSha),
            ),
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(
                1,
                bankSha,
                "test-only/topic/interview/bank.md",
                "Test-only bank",
                bank,
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
            )
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(
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
            PhoneSync(
                Api("http://127.0.0.1:1", { error("Practice fixture must not call the network") }) {},
                store,
                files,
            ) {}
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
    fun repeatedHeadingAndStrayAnswerWarningsOpenTheirExactBlocks() =
        runBlocking<Unit> {
            val dao = store.dao()
            // Another repository's bank at the fixture bank's path: its warnings open their own document.
            val path = "test-only/topic/interview/bank.md"
            dao.replaceDocuments(8, listOf(DocumentEntity(3, 8, path, "Other bank", bankSha)))
            val html =
                testPage(
                    bankSha,
                    answer("b0", "Stray", "stray") +
                        question(1, "Repeated", firstShort, answer("a1", "First answer", "first-answer")) +
                        block("b3", "h3", "Repeated"),
                )
            dao.saveNoteWithoutSearch(
                SavedPageEntity(3, bankSha, path, "Other bank", html, 0, renderFormat = BuildConfig.RENDER_FORMAT)
            )
            TestHostActivity.content = { MaterialTheme { PracticeTab(dao) { pushed += it } } }
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                val repeated = "Repeated — no Canonical line"
                awaitText("2 headings are not questions")
                assertNull(findText(root(), repeated))
                click("Show")
                awaitText(repeated)
                scenario.recreate()
                awaitText(repeated)
                click("Canonical answer: Stray")
                click(repeated)
                assertEquals(
                    listOf(
                        Screen.Reader(3, "Other bank", OpenAt.Block(bankSha, "b0")),
                        Screen.Reader(3, "Other bank", OpenAt.Block(bankSha, "b3")),
                    ),
                    pushed,
                )
                click("Hide")
                awaitText("Show")
                assertNull(findText(root(), repeated))
            }
        }

    @Test
    fun eachQuestionTakesItsOwnAnswerAMissComesBackUnsavedAndTheSessionSurvivesRecreation() =
        runBlocking<Unit> {
            val dao = store.dao()
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("2 questions left · Foundations · Test-only bank")
                assertAnswerHidden()
                click("Show answer")
                val first = shownAnswer()
                click("Again")
                val missed =
                    withTimeout(5_000) {
                        while (dao.waitingAnnotations().isEmpty() || dao.pendingReviews().isEmpty()) delay(20)
                        dao.waitingAnnotations().single()
                    }
                assertEquals(AnnotationType.CARD, missed.type)
                assertEquals("An identical question?", missed.question)
                assertEquals("Canonical answer: $first", missed.exactText)
                assertEquals(if (first == "First answer") "a1" else "a2", missed.blockId)
                assertEquals(bankSha, missed.sourceBlobSha)
                assertEquals(listOf(0), dao.pendingReviews().map { it.grade })

                // The miss went to the back; the other question is next, also after recreation. The grade is saved
                // before the queue moves on, and the count reads the same either way, so recreating only once the next
                // question is up (Show answer again) saves the moved queue.
                awaitText("Show answer")
                scenario.recreate()
                awaitText("2 questions left · Foundations · Test-only bank")
                assertAnswerHidden()
                click("Show answer")
                val second = shownAnswer()
                assertTrue("The other question comes next", second != first)
                click("Good")
                withTimeout(5_000) { while (dao.pendingReviews().size != 2) delay(20) }

                // The miss comes back; grading it again decides only whether it returns.
                awaitText("1 question left · Foundations · Test-only bank")
                click("Show answer")
                assertEquals(first, shownAnswer())
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
            val dao = store.dao()
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
    fun thePromptShowsItsMaterialTheAnswerWaitsForRevealAndEachLinkedSectionOpensItsNote() =
        runBlocking<Unit> {
            val dao = store.dao()
            val note = checkNotNull(dao.savedPage(1))
            val worked = "Test-only worked solution (free)."
            val html =
                testPage(
                    bankSha,
                    question(
                        1,
                        "Test-only multi-link question?",
                        firstShort,
                        """<p data-block-id="a1" data-anchor-text="Canonical answer: First answer and Second answer"><strong>Canonical answer:</strong> """ +
                            link("First answer", "first-answer") +
                            " and " +
                            link("Second answer", "second-answer") +
                            "</p>",
                        prompt =
                            block("e1", "p", "Test-only evidence:") +
                                """<pre data-block-id="x1" data-anchor-text="test-only output"><code>test-only output</code></pre>""",
                        worked =
                            """<p data-block-id="w1" data-anchor-text="Worked answer: $worked"><strong>Worked answer:</strong> """ +
                                """<a href="https://example.com/test-only">Test-only worked solution</a> (free).</p>""",
                    ),
                )
            dao.saveNoteWithoutSearch(note.copy(html = html))
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Test-only evidence:")
                awaitText("test-only output")
                assertAnswerHidden()
                assertNull(findText(root(), worked))
                click("How to grade")
                awaitText(GRADE_MEANING)
                click("OK")
                click("Show answer")
                awaitText(firstShort)
                awaitText("Test-only checklist point")
                awaitText(worked)
                awaitText("First answer")
                click("Second answer")
                assertEquals(
                    listOf(Screen.Reader(2, "Test-only core", OpenAt.Heading("second-answer"))),
                    pushed,
                )
            }
        }

    @Test
    fun aLinkToANoteMissingFromTheListIsReportedOnlyAfterReveal() =
        runBlocking<Unit> {
            store
                .dao()
                .replaceDocuments(
                    7,
                    listOf(DocumentEntity(1, 7, "test-only/topic/interview/bank.md", "Test-only bank", bankSha)),
                )
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Show answer")
                val problem =
                    "No note “test-only/topic/core.md” in this repository's saved list. Refresh the repository if it is new."
                assertNull(findText(root(), problem))
                click("Show answer")
                awaitText(problem)
                awaitText("Good")
            }
        }

    @Test
    fun aGradeWaitingForSyncSurvivesPoppingItsEntry() = runBlocking {
        val dao = store.dao()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(true)
        // A real local write holds PhoneSync's lock at its notification boundary, never a second DAO implementation.
        val sync =
            PhoneSync(
                Api("http://127.0.0.1:1", { error("Practice test must not call the network") }) {},
                store,
                files,
            ) {
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
            val holding = async(Dispatchers.IO) { sync.saveNewHighlight(Passage(bankSha, "b0", 0, 4, "Test"), 1, null) }
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

    /** Before Show answer, no part of any answer is in accessibility. */
    private fun assertAnswerHidden() {
        awaitText("Show answer")
        for (text in answerTexts) assertNull("No answer in accessibility before reveal: $text", findText(root(), text))
    }

    /** Which question's answer is shown: its own Short answer and linked section, never the other's. */
    private fun shownAnswer(): String = runBlocking {
        val label =
            withTimeout(5_000) {
                while (true) {
                    if (findText(root(), firstShort) != null) return@withTimeout "First answer"
                    if (findText(root(), secondShort) != null) return@withTimeout "Second answer"
                    delay(50)
                }
                error("Unreachable")
            }
        awaitText(label)
        awaitText("Test-only checklist point")
        val other = if (label == "First answer") "Second answer" else "First answer"
        assertNull("Only this question's linked section is offered", findText(root(), other))
        label
    }
}
