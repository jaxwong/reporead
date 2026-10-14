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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native review UI with test-only Room rows; no network, real account, or GitHub writes. */
@RunWith(AndroidJUnit4::class)
class ReviewLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun tenOfflineGradesPersistOnceAndRecreationPreservesRevealAndSessionCompletion() = runBlocking {
        val store = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LocalStore::class.java).build()
        try {
            val dao = store.library()
            val sha = "a".repeat(40)
            val html = "<main id=note>" + (1..10).joinToString("") {
                """<p data-block-id="b$it" data-anchor-text="Test-only answer $it in context.">Test-only answer $it in context.</p>"""
            } + "</main>"
            dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "test-only/review.md", "Test-only review", sha)))
            dao.saveNote(NoteRow(1, sha, sha, "test-only/review.md", "Test-only review", html, 0))
            dao.saveReviewLimit(ReviewLimitRow(sessionLimit = 40))
            for (i in 1..10) {
                val exact = "Test-only answer $i"
                dao.saveAnnotation(AnnotationRow("card$i", i.toLong(), 1, sha, "b$i", 0, exact.length, exact, null, 1, 0,
                    pending = false, rejection = null, type = "CARD", question = "Test-only question $i?", checkedBlobSha = sha))
            }
            val sync = Sync(Api("http://127.0.0.1:1") { error("Review fixture must not call the network") }, store,
                File(instrumentation.targetContext.cacheDir, "review-lifecycle-test"))
            ReaderTestActivity.content = {
                MaterialTheme { ReviewScreen(dao, sync, push = { error("Review fixture must not navigate") }, onBack = {}) }
            }
            try {
                ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                    click("Start review")
                    click("Reveal answer")
                    awaitText("Good")
                    scenario.recreate()
                    awaitText("Good")
                    for (i in 1..10) {
                        click("Good")
                        withTimeout(5_000) { while (dao.pendingReviews().size != i) delay(20) }
                        if (i < 10) click("Reveal answer")
                    }
                    awaitText("Session complete: 10 cards reviewed or skipped.")
                    scenario.recreate()
                    awaitText("Session complete: 10 cards reviewed or skipped.")
                    assertEquals(10, dao.reviews().first().size)
                    assertEquals(10, dao.pendingReviews().map { it.cardMutationId }.distinct().size)
                    assertTrue(dao.pendingReviews().all { it.grade == 4 && it.blobSha == sha })
                }
            } finally {
                instrumentation.waitForIdleSync()
                ReaderTestActivity.content = null
            }
        } finally { store.close() }
    }

    private fun findText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findText(it, text)?.let { found -> return found } }
        return null
    }

    private fun awaitText(text: String): AccessibilityNodeInfo = runBlocking {
        withTimeout(5_000) {
            while (true) {
                instrumentation.uiAutomation.rootInActiveWindow?.let { root -> findText(root, text)?.let { return@withTimeout it } }
                delay(50)
            }
            error("Unreachable")
        }
    }

    private fun click(text: String) {
        var node = awaitText(text)
        while (!node.isClickable) node = checkNotNull(node.parent) { "No clickable parent for $text" }
        assertTrue("Could not click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
