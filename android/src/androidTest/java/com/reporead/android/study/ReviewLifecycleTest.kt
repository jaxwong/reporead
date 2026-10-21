package com.reporead.android.study

import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.TestEntries
import com.reporead.android.TestHostActivity
import com.reporead.android.auth.StoredSession
import com.reporead.android.awaitText
import com.reporead.android.awaitTextStarting
import com.reporead.android.click
import com.reporead.android.core.network.Api
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.ReviewLimitRow
import com.reporead.android.data.saveNoteWithoutSearch
import com.reporead.android.findText
import com.reporead.android.reader.testPage
import com.reporead.android.root
import com.reporead.android.sync.Sync
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real native review UI with test-only Room rows; no real account or GitHub writes, and no server is reachable. */
@RunWith(AndroidJUnit4::class)
class ReviewLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sha = "a".repeat(40)
    private val bankPath = "test-only/interview/review.md"
    private lateinit var store: LocalStore
    private lateinit var sync: Sync

    private fun canonicalLine(i: Int) = "Canonical answer: Test-only core $i"

    private fun labelled(blockId: String, label: String, text: String = "") =
        """<p data-block-id="$blockId" data-anchor-text="$label $text"><strong>$label</strong> $text</p>"""

    /** Bank question [i] in the closed shape, its Canonical line in block `a$i`. */
    private fun bankQuestion(i: Int) =
        """<h3 data-block-id="q$i" data-anchor-text="Test-only question $i?">Test-only question $i?</h3>""" +
            labelled("t$i", "Interviewer is testing:", "Test-only recall $i.") +
            labelled("c$i", "A complete answer should:") +
            """<ul><li data-block-id="p$i" data-anchor-text="Test-only point $i">Test-only point $i</li></ul>""" +
            labelled("s$i", "Short answer:", "Test-only short answer $i.") +
            """<p data-block-id="a$i" data-anchor-text="${canonicalLine(i)}"><strong>Canonical answer:</strong> <a href="/note-link?path=test-only%2Fcore.md&amp;heading=answer">Test-only core $i</a></p>"""

    private fun bank(blobSha: String, questions: IntRange = 1..10) =
        testPage(blobSha, questions.joinToString("") { bankQuestion(it) })

    @Before
    fun open() = runBlocking {
        store = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LocalStore::class.java).build()
        val dao = store.library()
        dao.replaceDocuments(
            7,
            listOf(
                DocumentRow(1, 7, bankPath, "Test-only review", sha),
                DocumentRow(2, 7, "test-only/core.md", "Test-only core", "c".repeat(40)),
            ),
        )
        dao.saveNoteWithoutSearch(NoteRow(1, sha, bankPath, "Test-only review", bank(sha), 0))
        dao.saveReviewLimit(ReviewLimitRow(sessionLimit = 40))
        // No token and a closed local port: a server call fails as unreachable instead of leaving the phone.
        sync =
            Sync(
                Api("http://127.0.0.1:1", { StoredSession.Absent }) {},
                store,
                File(instrumentation.targetContext.cacheDir, "review-lifecycle-test"),
            ) {}
        showReview(push = { error("Review fixture must not navigate") })
    }

    @After
    fun close() {
        instrumentation.waitForIdleSync()
        TestHostActivity.content = null
        if (::store.isInitialized) store.close()
    }

    private fun showReview(topic: String? = null, push: (Screen) -> Unit) {
        val dao = store.library()
        TestHostActivity.content =
            TestEntries(Screen.Review(topic)).content { entry ->
                MaterialTheme { ReviewScreen(dao, sync, topic, entry.id, push = push, onBack = {}) }
            }
    }

    /** A card made from bank question [i] in version [madeIn], with the prompt it was asked with then. */
    private suspend fun card(
        i: Int,
        documentId: Long = 1,
        madeIn: String = sha,
        state: AnnotationState = AnnotationState.ACKNOWLEDGED,
    ) {
        val line = canonicalLine(i)
        store
            .library()
            .saveAnnotation(
                AnnotationRow(
                    "card$i",
                    if (state == AnnotationState.UNSENT) null else i.toLong(),
                    documentId,
                    madeIn,
                    "a$i",
                    0,
                    line.length,
                    line,
                    null,
                    1,
                    0,
                    state = state,
                    type = AnnotationType.CARD,
                    question = "Saved test-only prompt $i?",
                )
            )
    }

    @Test
    fun aBankCardAsksItsCurrentQuestionAndOpensItsLinkedSectionInTheNote() =
        runBlocking<Unit> {
            val dao = store.library()
            val line = "Canonical answer: Test-only core"
            val short = "Test-only review short answer."
            val edited =
                testPage(
                    sha,
                    """<h3 data-block-id="b1" data-anchor-text="Current test-only bank prompt?">Current test-only bank prompt?</h3>""" +
                        """<p data-block-id="e1" data-anchor-text="Test-only review evidence.">Test-only review evidence.</p>""" +
                        labelled("t1", "Interviewer is testing:", "Test-only recall.") +
                        labelled("c1", "A complete answer should:") +
                        """<ul><li data-block-id="i1" data-anchor-text="Test-only review point">Test-only review point</li></ul>""" +
                        labelled("s1", "Short answer:", short) +
                        """<p data-block-id="b2" data-anchor-text="$line"><strong>Canonical answer:</strong> <a href="/note-link?path=test-only%2Fcore.md&amp;heading=answer">Test-only core</a></p>""",
                )
            dao.saveNoteWithoutSearch(NoteRow(1, sha, bankPath, "Test-only review", edited, 0))
            dao.saveAnnotation(
                AnnotationRow(
                    "bank-card",
                    1,
                    1,
                    sha,
                    "b2",
                    0,
                    line.length,
                    line,
                    null,
                    1,
                    0,
                    state = AnnotationState.ACKNOWLEDGED,
                    type = AnnotationType.CARD,
                    question = "Old test-only bank prompt?",
                )
            )
            val pushed = mutableListOf<Screen>()
            showReview(push = { pushed += it })
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("Current test-only bank prompt?")
                awaitText("Test-only review evidence.")
                assertNull(findText(root(), "Old test-only bank prompt?"))
                for (text in
                    listOf(short, "Test-only review point", "Test-only core", "Good", "Open in note")) assertNull(
                    "No answer in accessibility before reveal: $text",
                    findText(root(), text),
                )
                click("Show answer")
                awaitText(short)
                awaitText("Test-only review point")
                awaitText("Good")
                scenario.recreate()
                awaitText(short)
                click("Test-only core")
                assertEquals(listOf(Screen.Reader(2, "Test-only core", OpenAt.Heading("answer"))), pushed)
            }
        }

    @Test
    fun tenOfflineGradesPersistOnceAndRecreationPreservesRevealAndSessionCompletion() =
        runBlocking<Unit> {
            val dao = store.library()
            for (i in 1..10) card(i)
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                awaitText("Show answer")
                assertNull(findText(root()) { it.startsWith("Test-only short answer") })
                assertNull(findText(root(), "Good"))
                assertNull(findText(root(), "Open in note"))
                click("Show answer")
                awaitText("Good")
                scenario.recreate()
                awaitText("Good")
                for (i in 1..10) {
                    click("Good")
                    withTimeout(5_000) { while (dao.pendingReviews().size != i) delay(20) }
                    if (i < 10) click("Show answer")
                }
                awaitText("Session complete: 10 cards reviewed.")
                scenario.recreate()
                awaitText("Session complete: 10 cards reviewed.")
                assertEquals(10, dao.reviews().first().size)
                assertEquals(10, dao.pendingReviews().map { it.cardMutationId }.distinct().size)
                assertTrue(dao.pendingReviews().all { it.grade == 4 && it.blobSha == sha })
            }
        }

    /**
     * The bank was edited elsewhere and its new version saved; the server has not placed the card there, because nobody
     * opened the bank. Its Canonical line is unchanged, so it is graded in the new version with nothing to confirm.
     */
    @Test
    fun aCardOfAnEditedBankIsGradedInTheNewVersionWithoutBeingConfirmed() =
        runBlocking<Unit> {
            val current = "b".repeat(40)
            val dao = store.library()
            dao.replaceDocuments(
                7,
                listOf(
                    DocumentRow(1, 7, bankPath, "Test-only review", current),
                    DocumentRow(2, 7, "test-only/core.md", "Test-only core", "c".repeat(40)),
                ),
            )
            dao.saveNoteWithoutSearch(NoteRow(1, current, bankPath, "Test-only review", bank(current), 0))
            card(1)
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Test-only question 1?")
                click("Show answer")
                awaitText("Test-only short answer 1.")
                click("Good")
                awaitText("Session complete: 1 card reviewed.")
                assertEquals(listOf(current), dao.pendingReviews().map { it.blobSha })
            }
        }

    @Test
    fun aCardWhoseCanonicalLineLeftTheBankOffersTheBankInsteadOfAGrade() =
        runBlocking<Unit> {
            val current = "b".repeat(40)
            val dao = store.library()
            dao.replaceDocuments(
                7,
                listOf(
                    DocumentRow(1, 7, bankPath, "Test-only review", current),
                    DocumentRow(2, 7, "test-only/core.md", "Test-only core", "c".repeat(40)),
                ),
            )
            dao.saveNoteWithoutSearch(NoteRow(1, current, bankPath, "Test-only review", bank(current, 2..10), 0))
            card(1)
            val pushed = mutableListOf<Screen>()
            showReview(push = { pushed += it })
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Saved test-only prompt 1?")
                awaitTextStarting("Its bank no longer has this question's Canonical line.")
                assertNull(findText(root(), "Show answer"))
                click("Open note")
            }
            assertEquals(listOf(Screen.Reader(1, "Test-only review", OpenAt.Highlight("card1"))), pushed)
            assertTrue(dao.reviews().first().isEmpty())
        }

    @Test
    fun aMissedCardComesBackInTheSessionWithoutASecondSavedGrade() =
        runBlocking<Unit> {
            val dao = store.library()
            card(1)
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("1 card left · Test-only review")
                click("Show answer")
                click("Again")
                withTimeout(5_000) { while (dao.pendingReviews().isEmpty()) delay(20) }
                awaitText("1 card left · Test-only review")
                click("Show answer")
                awaitText(
                    "Asked again: its first grade is saved. This grade only decides whether it comes back in this session."
                )
                click("Good")
                awaitText("Session complete: 1 card reviewed.")
                assertEquals(listOf(0), dao.reviews().first().map { it.grade })
            }
        }

    /** A card the server refuses during a session is never sent again, so syncing cannot fix it. */
    @Test
    fun aCardRefusedDuringTheSessionSaysSo() =
        runBlocking<Unit> {
            val dao = store.library()
            card(1, state = AnnotationState.UNSENT)
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Show answer")
                dao.rejectAnnotation("card1", "Test-only refusal.")
                awaitTextStarting("The server refused this card")
            }
        }

    @Test
    fun reviewOffersEachTopicWithCardsDueAndASessionAsksOnlyThatTopicsCards() =
        runBlocking<Unit> {
            val dao = store.library()
            val otherPath = "other/interview/bank.md"
            dao.replaceDocuments(
                7,
                listOf(
                    DocumentRow(1, 7, bankPath, "Test-only review", sha),
                    DocumentRow(2, 7, "test-only/core.md", "Test-only core", "c".repeat(40)),
                    DocumentRow(3, 7, otherPath, "Test-only other bank", sha),
                ),
            )
            dao.saveNoteWithoutSearch(NoteRow(3, sha, otherPath, "Test-only other bank", bank(sha, 3..3), 0))
            card(1)
            card(2)
            card(3, documentId = 3)
            val pushed = mutableListOf<Screen>()
            TestHostActivity.content =
                TestEntries(Screen.ReviewTopics).content {
                    MaterialTheme { ReviewTopicsScreen(dao, push = { pushed += it }, onBack = {}) }
                }
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("All topics")
                awaitText("3 due")
                awaitText("test-only")
                awaitText("2 due")
                awaitText("1 due")
                click("other")
            }
            assertEquals(listOf(Screen.Review("other")), pushed)
            showReview("test-only") { error("Review fixture must not navigate") }
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("2 cards left · Test-only review")
                assertNull(findText(root(), "Test-only question 3?"))
            }
        }
}
