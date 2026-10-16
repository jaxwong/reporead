package com.reporead.android.library

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.Screen
import com.reporead.android.core.network.Api
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.reader.ReaderTestActivity
import com.reporead.android.sync.RENDER_FORMAT
import com.reporead.android.sync.Sync
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** A table too wide for the screen, so the page gives it a Full screen link. */
    private val wideTable =
        "<table><tr>" + (1..20).joinToString("") { "<td>test-only-wide-column-$it</td>" } + "</tr></table>"
    /** What the session asked the app to open. */
    private val pushed = mutableListOf<Screen>()

    private fun page(sha: String, body: String) =
        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="/assets/reader.css"><script defer src="/assets/reader.js"></script></head>
        <body data-source-blob-sha="$sha" data-max-diagram-chars="20000" data-max-edges="200">
        <p id="render-status"></p><main id="note">$body</main></body></html>"""

    @Before
    fun open() = runBlocking {
        val context = instrumentation.targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        files = File(context.cacheDir, "practice-session-test")
        check(files.mkdirs() || files.isDirectory) { "Cannot create test-only directory $files" }
        val dao = store.library()
        // Both questions read the same; each must take its own answer line and section.
        val bank =
            page(
                bankSha,
                block("b0", "h1", "Test-only bank") +
                    block("b1", "h2", "1. Foundations") +
                    block("b2", "h3", "1. An identical question?") +
                    answer("b3", "First answer", "first-answer") +
                    block("b4", "h3", "2. An identical question?") +
                    answer("b5", "Second answer", "second-answer"),
            )
        val core =
            page(
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
                renderFormat = RENDER_FORMAT,
            )
        )
        dao.saveNote(
            NoteRow(2, coreSha, "test-only/topic/core.md", "Test-only core", core, 0, renderFormat = RENDER_FORMAT)
        )
        val sync =
            Sync(Api("http://127.0.0.1:1", { error("Practice fixture must not call the network") }) {}, store, files) {}
        ReaderTestActivity.content = {
            MaterialTheme {
                PracticeScreen(dao, sync, "test-only/topic", push = { pushed += it }, onBack = {})
            }
        }
    }

    @After
    fun close() {
        instrumentation.waitForIdleSync()
        ReaderTestActivity.content = null
        if (::store.isInitialized) store.close()
        if (::files.isInitialized) check(files.deleteRecursively()) { "Cannot delete test-only directory $files" }
    }

    @Test
    fun eachQuestionTakesItsOwnAnswerAMissComesBackUnsavedAndTheSessionSurvivesRecreation() =
        runBlocking<Unit> {
            val dao = store.library()
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                awaitText("2 questions left · Foundations · Test-only bank")
                assertNull("Nothing of the answer shows before the attempt", findWebView(scenario))
                click("Show answer")
                val first = shownAnswer(scenario)
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
                click("Show answer")
                val second = shownAnswer(scenario)
                assertTrue("The other question comes next", second != first)
                click("Good")
                withTimeout(5_000) { while (dao.pendingReviews().size != 2) delay(20) }

                // The miss comes back; grading it again decides only whether it returns.
                awaitText("1 question left · Foundations · Test-only bank")
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
            ActivityScenario.launch(ReaderTestActivity::class.java).use {
                awaitText(
                    "No new questions here: each saved question already has a card. They come back under Review when due."
                )
            }
        }

    @Test
    fun fullScreenInAnAnswerOpensThatFigureOfTheLinkedNote() =
        runBlocking<Unit> {
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                click("Show answer")
                // The page numbers figures over the whole note, as the full-screen view does, though it shows one
                // section.
                val figure = if (shownAnswer(scenario) == "First answer") "table-0" else "table-1"
                val link =
                    evaluate(
                        scenario,
                        """
                const row = document.querySelector('[data-figure="$figure"]').previousElementSibling;
                const rect = row.querySelector('a').getBoundingClientRect();
                return {hidden: row.hidden, x: rect.left + rect.width / 2, y: rect.top + rect.height / 2};
            """
                            .trimIndent(),
                    )
                assertTrue("The table is wide enough for a Full screen link: $link", !link.getBoolean("hidden"))
                tapPage(scenario, link.getDouble("x"), link.getDouble("y"))
                withTimeout(5_000) { while (pushed.isEmpty()) delay(20) }
                assertEquals(listOf(Screen.Figure(2, coreSha, figure, "Test-only core")), pushed)
            }
        }

    /** A real touch on the answer page at CSS pixel ([x], [y]); the app follows only links the user tapped. */
    private fun tapPage(scenario: ActivityScenario<ReaderTestActivity>, x: Double, y: Double) {
        var screenX = 0f
        var screenY = 0f
        scenario.onActivity { activity ->
            val view = checkNotNull(findWebView(activity.window.decorView)) { "Answer page is missing" }
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val density = activity.resources.displayMetrics.density
            screenX = origin[0] + (x * density).toFloat()
            screenY = origin[1] + (y * density).toFloat()
        }
        val down = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event =
                MotionEvent.obtain(down, at, action, screenX, screenY, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
            assertTrue("Could not inject a tap", instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    /** Which linked section the revealed answer shows; it must be that section alone, from the saved copy. */
    private fun shownAnswer(scenario: ActivityScenario<ReaderTestActivity>): String = runBlocking {
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
                return@runBlocking label
            }
            delay(100)
        }
        throw AssertionError("Answer section $label never showed; last=$last")
    }

    private fun evaluate(scenario: ActivityScenario<ReaderTestActivity>, script: String): JSONObject {
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

    private fun findWebView(scenario: ActivityScenario<ReaderTestActivity>): WebView? {
        var found: WebView? = null
        scenario.onActivity { found = findWebView(it.window.decorView) }
        return found
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let {
                return it
            }
        return null
    }

    private fun root(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let {
            findText(it, text)?.let { found ->
                return found
            }
        }
        return null
    }

    private fun awaitText(text: String): AccessibilityNodeInfo = runBlocking {
        try {
            withTimeout(10_000) {
                while (true) {
                    findText(root(), text)?.let {
                        return@withTimeout it
                    }
                    delay(50)
                }
                error("Unreachable")
            }
        } catch (error: TimeoutCancellationException) {
            fun texts(node: AccessibilityNodeInfo?): List<String> =
                if (node == null) emptyList()
                else
                    listOfNotNull(node.text?.toString()) +
                        (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            throw AssertionError("Timed out waiting for '$text'; visible texts=${texts(root())}", error)
        }
    }

    private fun click(text: String) {
        var node = awaitText(text)
        while (!node.isClickable) node = checkNotNull(node.parent) { "No clickable parent for $text" }
        assertTrue("Could not click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
