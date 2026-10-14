package com.reporead.android.library

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.core.network.Api
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.ReviewLimitRow
import com.reporead.android.reader.ReaderTestActivity
import com.reporead.android.sync.Sync
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native review UI with test-only Room rows; no real account or GitHub writes, and no server is reachable. */
@RunWith(AndroidJUnit4::class)
class ReviewLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sha = "a".repeat(40)
    private lateinit var store: LocalStore

    @Before fun open() = runBlocking {
        store = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LocalStore::class.java).build()
        val dao = store.library()
        val html = "<main id=note>" + (1..10).joinToString("") {
            """<p data-block-id="b$it" data-anchor-text="Test-only answer $it in context.">Test-only answer $it in context.</p>"""
        } + "</main>"
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "test-only/review.md", "Test-only review", sha)))
        dao.saveNote(NoteRow(1, sha, sha, "test-only/review.md", "Test-only review", html, 0))
        dao.saveReviewLimit(ReviewLimitRow(sessionLimit = 40))
        // No token and a closed local port: a server call fails as unreachable instead of leaving the phone.
        val sync = Sync(Api("http://127.0.0.1:1") { null }, store, File(instrumentation.targetContext.cacheDir, "review-lifecycle-test"))
        ReaderTestActivity.content = {
            MaterialTheme { ReviewScreen(dao, sync, push = { error("Review fixture must not navigate") }, onBack = {}) }
        }
    }

    @After fun close() {
        instrumentation.waitForIdleSync()
        ReaderTestActivity.content = null
        if (::store.isInitialized) store.close()
    }

    private suspend fun card(i: Int, checkedBlobSha: String = sha) {
        val exact = "Test-only answer $i"
        store.library().saveAnnotation(AnnotationRow("card$i", i.toLong(), 1, sha, "b$i", 0, exact.length, exact, null, 1, 0,
            pending = false, rejection = null, type = "CARD", question = "Test-only question $i?", checkedBlobSha = checkedBlobSha))
    }

    @Test fun tenOfflineGradesPersistOnceAndRecreationPreservesRevealAndSessionCompletion() = runBlocking<Unit> {
        val dao = store.library()
        for (i in 1..10) card(i)
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
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

    @Test fun aMissedCardComesBackInTheSessionWithoutASecondSavedGrade() = runBlocking<Unit> {
        val dao = store.library()
        card(1)
        ActivityScenario.launch(ReaderTestActivity::class.java).use {
            awaitText("1 card left · Test-only review")
            click("Show answer")
            click("Again")
            withTimeout(5_000) { while (dao.pendingReviews().isEmpty()) delay(20) }
            awaitText("1 card left · Test-only review")
            click("Show answer")
            awaitText("Asked again: its first grade is saved. This grade only decides whether it comes back in this session.")
            click("Good")
            awaitText("Session complete: 1 card reviewed.")
            assertEquals(listOf(0), dao.reviews().first().map { it.grade })
        }
    }

    @Test fun aCardToCheckShowsItsAnswerInPlaceAndConfirmingNeedsTheServer() = runBlocking<Unit> {
        val dao = store.library()
        // Checked in an older version of the note: it is due, but must be confirmed before it can be graded.
        card(1, checkedBlobSha = "b".repeat(40))
        ActivityScenario.launch(ReaderTestActivity::class.java).use {
            awaitText("The note changed since this card was checked. Is this still its answer?")
            awaitText("Test-only answer 1 in context.")
            assertNull("No grade before it is confirmed", findText(root(), "Good"))
            click("Answer is still right")
            awaitTextStarting("Not confirmed: Can't reach RepoRead's server.")
            assertEquals("b".repeat(40), dao.annotation("card1")?.checkedBlobSha)
            assertTrue(dao.reviews().first().isEmpty())
        }
    }

    private fun root(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow

    private fun findText(node: AccessibilityNodeInfo?, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.let(matches) == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findText(it, matches)?.let { found -> return found } }
        return null
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String) = findText(node) { it == text }

    private fun awaitMatch(description: String, matches: (String) -> Boolean): AccessibilityNodeInfo = runBlocking {
        try {
            withTimeout(10_000) {
                while (true) {
                    findText(root(), matches)?.let { return@withTimeout it }
                    delay(50)
                }
                error("Unreachable")
            }
        } catch (error: TimeoutCancellationException) {
            fun texts(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else listOfNotNull(node.text?.toString()) +
                (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            throw AssertionError("Timed out waiting for $description; visible texts=${texts(root())}", error)
        }
    }

    private fun awaitText(text: String) = awaitMatch("'$text'") { it == text }

    private fun awaitTextStarting(prefix: String) = awaitMatch("text starting '$prefix'") { it.startsWith(prefix) }

    private fun click(text: String) {
        var node = awaitText(text)
        while (!node.isClickable) node = checkNotNull(node.parent) { "No clickable parent for $text" }
        assertTrue("Could not click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
