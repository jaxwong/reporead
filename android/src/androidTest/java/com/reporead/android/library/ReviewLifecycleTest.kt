package com.reporead.android.library

import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.OpenAt
import com.reporead.android.Screen
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
import com.reporead.android.findText
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
    private val html =
        "<main id=note>" +
            (1..10).joinToString("") {
                """<p data-block-id="b$it" data-anchor-text="Test-only answer $it in context.">Test-only answer $it in context.</p>"""
            } +
            "</main>"
    private lateinit var store: LocalStore

    @Before
    fun open() = runBlocking {
        store = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LocalStore::class.java).build()
        val dao = store.library()
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "test-only/review.md", "Test-only review", sha)))
        dao.saveNote(NoteRow(1, sha, "test-only/review.md", "Test-only review", html, 0))
        dao.saveReviewLimit(ReviewLimitRow(sessionLimit = 40))
        // No token and a closed local port: a server call fails as unreachable instead of leaving the phone.
        val sync =
            Sync(
                Api("http://127.0.0.1:1", { StoredSession.Absent }) {},
                store,
                File(instrumentation.targetContext.cacheDir, "review-lifecycle-test"),
            ) {}
        TestHostActivity.content = {
            MaterialTheme { ReviewScreen(dao, sync, push = { error("Review fixture must not navigate") }, onBack = {}) }
        }
    }

    @After
    fun close() {
        instrumentation.waitForIdleSync()
        TestHostActivity.content = null
        if (::store.isInitialized) store.close()
    }

    private suspend fun card(i: Int, checkedBlobSha: String = sha) {
        val exact = "Test-only answer $i"
        store
            .library()
            .saveAnnotation(
                AnnotationRow(
                    "card$i",
                    i.toLong(),
                    1,
                    sha,
                    "b$i",
                    0,
                    exact.length,
                    exact,
                    null,
                    1,
                    0,
                    state = AnnotationState.ACKNOWLEDGED,
                    type = AnnotationType.CARD,
                    question = "Test-only question $i?",
                    checkedBlobSha = checkedBlobSha,
                )
            )
    }

    @Test
    fun tenOfflineGradesPersistOnceAndRecreationPreservesRevealAndSessionCompletion() =
        runBlocking<Unit> {
            val dao = store.library()
            for (i in 1..10) card(i)
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
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
     * The note was edited and its new version saved, but the server places a card in a new version only when the note's
     * highlights are listed, which opening the note does and a sync does not: the card offers the note.
     */
    @Test
    fun aCardOfAnEditedNoteOffersTheNoteWhereItsAnswerIsFound() =
        runBlocking<Unit> {
            val current = "b".repeat(40)
            val dao = store.library()
            dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "test-only/review.md", "Test-only review", current)))
            dao.saveNote(NoteRow(1, current, "test-only/review.md", "Test-only review", html, 0))
            card(1)
            val sync =
                Sync(
                    Api("http://127.0.0.1:1", { StoredSession.Absent }) {},
                    store,
                    File(instrumentation.targetContext.cacheDir, "review-lifecycle-test"),
                ) {}
            val pushed = mutableListOf<Screen>()
            TestHostActivity.content = {
                MaterialTheme { ReviewScreen(dao, sync, push = { pushed += it }, onBack = {}) }
            }
            ActivityScenario.launch(TestHostActivity::class.java).use { click("Open note") }
            assertEquals(listOf(Screen.Reader(1, "Test-only review", OpenAt.Highlight("card1"))), pushed)
        }

    /** Reading the saved page takes a moment; until it is read the answer is unknown, not unverifiable. */
    @Test
    fun revealingAnAnswerNeverFirstSaysItCannotBeVerified() =
        runBlocking<Unit> {
            val dao = store.library()
            val filler =
                (11..4_000).joinToString("") {
                    """<p data-block-id="b$it" data-anchor-text="Filler $it.">Filler $it.</p>"""
                }
            dao.saveNote(
                NoteRow(1, sha, "test-only/review.md", "Test-only review", html.replace("</main>", "$filler</main>"), 0)
            )
            card(1)
            ActivityScenario.launch(TestHostActivity::class.java).use {
                click("Show answer")
                val unverifiable = "The saved page cannot verify this answer in context. Open the note to check it."
                withTimeout(10_000) {
                    while (findText(root(), "Good") == null) assertNull(unverifiable, findText(root(), unverifiable))
                }
            }
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

    @Test
    fun aCardToCheckShowsItsAnswerInPlaceAndConfirmingNeedsTheServer() =
        runBlocking<Unit> {
            val dao = store.library()
            // Checked in an older version of the note: it is due, but must be confirmed before it can be graded.
            card(1, checkedBlobSha = "b".repeat(40))
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("The note changed since this card was checked. Is this still its answer?")
                awaitText("Test-only answer 1 in context.")
                assertNull("No grade before it is confirmed", findText(root(), "Good"))
                click("Answer is still right")
                awaitTextStarting("Not confirmed: Can't reach RepoRead's server.")
                assertEquals("b".repeat(40), dao.annotation("card1")?.checkedBlobSha)
                assertTrue(dao.reviews().first().isEmpty())
            }
        }

    /** A card the server refuses during a session is never sent again, so syncing cannot fix it. */
    @Test
    fun aCardRefusedDuringTheSessionSaysSoInsteadOfOfferingASync() =
        runBlocking<Unit> {
            val dao = store.library()
            val exact = "Test-only answer 1"
            dao.saveAnnotation(
                AnnotationRow(
                    "card1",
                    null,
                    1,
                    sha,
                    "b1",
                    0,
                    exact.length,
                    exact,
                    null,
                    1,
                    0,
                    state = AnnotationState.UNSENT,
                    type = AnnotationType.CARD,
                    question = "Test-only question 1?",
                    checkedBlobSha = sha,
                )
            )
            ActivityScenario.launch(TestHostActivity::class.java).use {
                awaitText("Show answer")
                dao.rejectAnnotation("card1", "Test-only refusal.")
                awaitTextStarting("The server refused this card")
                assertNull(findText(root()) { it.startsWith("This card hasn't reached the server yet") })
            }
        }
}
