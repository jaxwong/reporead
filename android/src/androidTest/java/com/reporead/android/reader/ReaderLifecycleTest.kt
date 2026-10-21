package com.reporead.android.reader

import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
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
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.RepositoryRow
import com.reporead.android.data.saveNoteWithoutSearch
import com.reporead.android.findText
import com.reporead.android.findWebView
import com.reporead.android.root
import com.reporead.android.sync.Sync
import com.reporead.android.type
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real ReaderScreen, Activity teardown and Room writes; isolated test-only data, zero network calls. */
@RunWith(AndroidJUnit4::class)
class ReaderLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sha = "a".repeat(40)
    private lateinit var store: LocalStore
    private lateinit var files: File
    private lateinit var sync: Sync
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    /** The reader's entries; a new entry for a new screen, as when the app navigates. */
    private val entries = TestEntries(Screen.Reader(1, "Test-only lifecycle fixture"))
    private val title = "Test-only lifecycle fixture"
    private var readerReady = false
    /** Like RepoReadApplication.scope, it outlives activity recreation. */
    private val appScope = MainScope()

    @Before
    fun open() = runBlocking {
        val context = instrumentation.targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        files = File(context.cacheDir, "reader-lifecycle-test")
        check(files.mkdirs() || files.isDirectory) { "Cannot create test-only directory $files" }
        val dao = store.library()
        dao.replaceRepositories(listOf(RepositoryRow(7, "test-only/lifecycle", 1, sha)))
        val path = "test-only/lifecycle.md"
        fun block(id: Int, tag: String, text: String) =
            """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""
        val body =
            block(0, "h1", title) +
                block(1, "h2", "Start") +
                (2..5).joinToString("") { block(it, "p", "Test-only opening $it.") } +
                block(6, "h2", "Answer") +
                (7..60).joinToString("") {
                    if (it == 20) block(it, "h3", "Answer") else block(it, "p", "Test-only paragraph $it. ".repeat(20))
                } +
                // A same-note link, and a block whose 64-unit prefix would end inside an emoji.
                """<p data-block-id="b61" data-anchor-text="Back to the answer"><a href="#answer">Back to the answer</a></p>""" +
                block(62, "p", "x".repeat(63) + "\uD83D\uDE00 after the emoji") +
                // Enough text below, so those blocks can reach the top of the screen.
                (63..80).joinToString("") { block(it, "p", "Test-only closing paragraph $it. ".repeat(20)) } +
                // A footnote whose definition links a heading of this note, as the renderer marks them.
                """<p data-block-id="b81" data-anchor-text="See the footnote[^n].">See the footnote<sup class="fn-ref" data-footnote="n" data-label="1"><span class="wl-hidden">[^n]</span></sup>.</p>""" +
                """<p data-block-id="b82" data-anchor-text="[^n]: Back to the answer"><span class="fn-def" data-footnote="n" data-label="1"><span class="wl-hidden">[^n]:</span></span> <a href="#answer">Back to the answer</a></p>"""
        val html = testPage(sha, body)
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, path, title, sha)))
        dao.saveNoteWithoutSearch(
            NoteRow(1, sha, path, title, html, 0, renderFormat = BuildConfig.RENDER_FORMAT, snapshotCommitSha = sha)
        )
        val api = Api("http://127.0.0.1:1", { error("Lifecycle fixture must not call the network") }) {}
        sync = Sync(api, store, files) {}
        TestHostActivity.content = entries.content { entry ->
            MaterialTheme {
                ReaderScreen(
                    sync,
                    dao,
                    appScope,
                    signedIn = false,
                    entry.id,
                    screen = entry.screen as Screen.Reader,
                    onBack = {},
                    push = { error("Lifecycle fixture must not navigate") },
                )
            }
        }
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
        awaitReader(0)
        awaitSaved(0)
        readerReady = true
    }

    /** Opens [screen] in a new entry in place of the reader shown. */
    private fun open(screen: Screen.Reader) = entries.replaceTop(screen, "test-only-${UUID.randomUUID()}")

    @After
    fun close() = runBlocking {
        if (readerReady) {
            val lastSavedAt = checkNotNull(store.library().reading(1)).lastReadAt
            // ActivityScenario.close does not await WebView callbacks or Room writes. Dispose the reader first and
            // observe its final save before closing the database.
            scenario.onActivity { entries.shown.value = false }
            try {
                withTimeout(5_000) {
                    while (checkNotNull(store.library().reading(1)).lastReadAt <= lastSavedAt) delay(10)
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError(
                    "Reader teardown did not save after $lastSavedAt; current=${store.library().reading(1)}",
                    error,
                )
            }
        }
        if (::scenario.isInitialized) scenario.close()
        instrumentation.waitForIdleSync()
        TestHostActivity.content = null
        appScope.cancel()
        if (::store.isInitialized) store.close()
        if (::files.isInitialized) check(files.deleteRecursively()) { "Cannot delete test-only directory $files" }
    }

    @Test
    fun releasingAReaderDestroysItsWebViewAndSavesItsPosition(): Unit = runBlocking {
        var page: WebView? = null
        scenario.onActivity { page = findWebView(it.window.decorView) }
        val view = checkNotNull(page)
        withContext(Dispatchers.Main) { assertNotNull(view.webViewRenderProcess) }
        val lastSavedAt = checkNotNull(store.library().reading(1)).lastReadAt
        scenario.onActivity { entries.shown.value = false }
        withTimeout(5_000) {
            while (withContext(Dispatchers.Main) { view.webViewRenderProcess != null }) delay(20)
        }
        withTimeout(5_000) {
            while (checkNotNull(store.library().reading(1)).lastReadAt <= lastSavedAt) delay(20)
        }
        readerReady = false
    }

    @Test
    fun aLinkedHeadingIsShownOnceAndRecreationRestoresTheReadingPositionInstead() {
        scenario.onActivity { open(Screen.Reader(1, title, OpenAt.Heading("Answer"))) }
        awaitReader(6)
        awaitSaved(6)
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        scenario.recreate()
        awaitReader(12)
        awaitSaved(12)
    }

    @Test
    fun aBankWarningOpensTheLaterIdenticalHeadingOnceAndRecreationKeepsItsPlace() {
        scenario.onActivity { open(Screen.Reader(1, title, OpenAt.Block(sha, "b20"))) }
        awaitReader(20)
        awaitSaved(20)
        evaluate("window.reporead.showBlock('b30'); return {};")
        awaitSaved(30)
        scenario.recreate()
        awaitReader(30)
        awaitSaved(30)
    }

    @Test
    fun aSearchResultHighlightsItsExactPhraseOnceAndRecreationKeepsItsPlace() {
        val text = "paragraph 21"
        scenario.onActivity {
            open(Screen.Reader(1, title, OpenAt.Search(Passage(sha, "b21", 10, 22, text))))
        }
        val shown = awaitHighlightInView(SEARCH_TARGET_KEY)
        awaitSaved(shown)
        evaluate("window.reporead.showBlock('b30'); return {};")
        awaitSaved(30)
        scenario.recreate()
        awaitReader(30)
        awaitSaved(30)
    }

    @Test
    fun findInNoteHighlightsOneOccurrenceAndMovesThroughTheMatches() = runBlocking {
        click("Find in note")
        type("paragraph 21.")
        awaitText("1 of 20")
        awaitHighlightInView(SEARCH_TARGET_KEY)
        click("Next match")
        awaitText("2 of 20")
        click("Previous match")
        awaitText("1 of 20")
        click("Previous match")
        awaitText("20 of 20")
        click("Close find")
        withTimeout(5_000) {
            while (
                evaluate(
                        "return {matches: document.querySelectorAll('#note mark[data-key=\"$SEARCH_TARGET_KEY\"]').length};"
                    )
                    .getInt("matches") != 0
            ) delay(50)
        }
        click("Find in note")
        type("No test-only match")
        awaitText("No matches")
        click("Close find")
    }

    @Test
    fun aWarningFromAnotherVersionDoesNotJumpToAReusedBlockId() {
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        scenario.onActivity {
            open(Screen.Reader(1, title, OpenAt.Block("b".repeat(40), "b20")))
        }
        awaitText("That location is not in this version of the note. Open it again from its current source.")
        awaitReader(12)
        awaitSaved(12)
        assertSavedStays(12)
    }

    @Test
    fun aWarningWhoseBlockIsMissingKeepsTheSavedPositionAndExplainsWhy() {
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        scenario.onActivity { open(Screen.Reader(1, title, OpenAt.Block(sha, "gone"))) }
        awaitText("That location is not in this version of the note. Open it again from its current source.")
        awaitReader(12)
        awaitSaved(12)
        assertSavedStays(12)
    }

    @Test
    fun aNotebookPassageIsShownOnceInsteadOfTheReadingPosition() {
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        val passage = "Test-only paragraph 50."
        runBlocking {
            store
                .library()
                .saveAnnotation(
                    AnnotationRow(
                        mutationId = "test-only-highlight",
                        serverId = null,
                        documentId = 1,
                        sourceBlobSha = sha,
                        blockId = "b50",
                        startOffset = 0,
                        endOffset = passage.length,
                        exactText = passage,
                        note = null,
                        version = 1,
                        createdAt = 0,
                        state = AnnotationState.UNSENT,
                    )
                )
        }
        scenario.onActivity {
            open(Screen.Reader(1, title, OpenAt.Highlight("test-only-highlight")))
        }
        val shown = awaitHighlightInView("test-only-highlight")
        assertTrue("Opened with block $shown at the top, not at the passage in block 50", shown in 13..50)
        awaitSaved(shown)
        scenario.recreate()
        awaitReader(shown)
        awaitSaved(shown)
    }

    @Test
    fun aSameNoteLinkScrollsToItsHeadingAndPrefixesKeepWholeCharacters() {
        val prefix =
            evaluate("window.reporead.showBlock('b62'); return {prefix: window.reporead.position().anchor.textPrefix};")
                .getString("prefix")
        assertEquals("x".repeat(63), prefix)
        evaluate("window.reporead.showBlock('b61'); document.querySelector('a[href=\"#answer\"]').click(); return {};")
        awaitReader(6)
        awaitSaved(6)
        // A Copy link in the note's own text can name a block that is not code; the page answers null instead of
        // throwing.
        assertTrue(evaluate("return {text: window.reporead.codeText('b5')};").isNull("text"))
    }

    /** The footnote sheet is outside the note; a same-note link in it is followed here too, never by the browser. */
    @Test
    fun aSameNoteLinkInAFootnoteScrollsToItsHeading() {
        evaluate(
            "window.reporead.showBlock('b81'); document.querySelector('.fn-ref').click(); " +
                "document.querySelector('#footnote a').click(); return {};"
        )
        awaitReader(6)
        assertEquals("", evaluate("return {hash: location.hash};").getString("hash"))
    }

    /**
     * A link to a heading that is gone opens the note where it was last read, as an ordinary open does, instead of at
     * the top, which the save after opening would have made the reader's place.
     */
    @Test
    fun aLinkToAMissingHeadingOpensWhereTheNoteWasLastRead() {
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        var previous: WebView? = null
        scenario.onActivity {
            previous = findWebView(it.window.decorView)
            open(Screen.Reader(1, title, OpenAt.Heading("Test-only heading that is gone")))
        }
        // The previous page is still at block 12 until it is released, so wait for the new one before reading it.
        runBlocking {
            withTimeout(10_000) {
                while (true) {
                    var shown: WebView? = null
                    scenario.onActivity { shown = findWebView(it.window.decorView) }
                    if (shown != null && shown !== previous) break
                    delay(20)
                }
            }
        }
        awaitReader(12)
        awaitSaved(12)
    }

    /** The page is redrawn when what it draws changes, not on every write to the annotations table. */
    @Test
    fun aWriteForAnotherNoteDoesNotRedrawThisOnesHighlights() =
        runBlocking<Unit> {
            evaluate(
                "const draw = window.reporead.highlight; window.drawn = 0; " +
                    "window.reporead.highlight = rows => { window.drawn++; return draw(rows); }; return {};"
            )
            // Past the reader's first draws, so only the writes below count.
            delay(500)
            evaluate("window.drawn = 0; return {};")
            val dao = store.library()
            fun highlight(key: String, documentId: Long, block: String) =
                AnnotationRow(
                    key,
                    null,
                    documentId,
                    sha,
                    block,
                    0,
                    9,
                    "Test-only",
                    null,
                    1,
                    0,
                    state = AnnotationState.UNSENT,
                )
            dao.saveAnnotation(highlight("test-only-elsewhere", 2, "b2"))
            // Apart, so Room reports the two writes separately.
            delay(1_000)
            dao.saveAnnotation(highlight("test-only-here", 1, "b2"))
            withTimeout(10_000) { while (evaluate("return {n: window.drawn};").getInt("n") == 0) delay(50) }
            delay(500)
            assertEquals(1, evaluate("return {n: window.drawn};").getInt("n"))
        }

    /** A page script that throws is raised with the page's own error and the expression, never read as a null. */
    @Test
    fun aPageScriptThatThrowsIsRaisedWithThePagesError() =
        runBlocking<Unit> {
            var page: WebView? = null
            scenario.onActivity { page = findWebView(it.window.decorView) }
            val view = checkNotNull(page)
            assertEquals(
                0,
                withContext(Dispatchers.Main) { view.evaluate("window.reporead.position().anchor.blockIndex") },
            )
            val failure =
                withContext(Dispatchers.Main) {
                    runCatching { view.evaluate("window.reporead.configureStudy({collapsedHeadings: ['b999']})") }
                        .exceptionOrNull()
                }
            assertTrue("$failure", failure is IllegalStateException)
            assertTrue("$failure", failure!!.message!!.contains("Study heading b999 missing"))
        }

    /** A renderer can crash or be reclaimed; the reader says so, stops offering the page's controls, and lives on. */
    @Test
    fun aPageWhoseRendererStopsIsReplacedAndSavesNothing() {
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        awaitText("Outline")
        scenario.onActivity { activity ->
            checkNotNull(findWebView(activity.window.decorView)).loadUrl("chrome://crash")
        }
        runBlocking {
            withTimeout(10_000) {
                while (true) {
                    var present = true
                    scenario.onActivity { activity -> present = findWebView(activity.window.decorView) != null }
                    if (!present) break
                    delay(50)
                }
                while (findText(root(), "Outline") != null) delay(50)
            }
        }
        assertSavedStays(12)
        // The page is gone, so there is no final save to wait for at teardown.
        readerReady = false
    }

    /** A note being written, and a highlight being reattached, survive recreation with what was typed. */
    @Test
    fun aNoteBeingEditedAndAHighlightBeingReattachedSurviveRecreation() {
        runBlocking {
            store
                .library()
                .saveAnnotation(
                    AnnotationRow(
                        mutationId = "test-only-acknowledged",
                        serverId = 5,
                        documentId = 1,
                        // Placed in another version, so it is not drawn here and Reattach is offered.
                        sourceBlobSha = "b".repeat(40),
                        blockId = "b2",
                        startOffset = 0,
                        endOffset = 9,
                        exactText = "Test-only",
                        note = null,
                        version = 1,
                        createdAt = 0,
                        state = AnnotationState.ACKNOWLEDGED,
                    )
                )
        }
        // Away from the top, so finding the place again after recreation shows the page has opened.
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        click("Highlights and notes")
        click("Edit note")
        type("Test-only half-typed note")
        scenario.recreate()
        awaitText("Test-only half-typed note")
        click("Cancel")
        click("Reattach")
        val placing = "Select the passage for “Test-only”, then choose Reattach here."
        awaitText(placing)
        scenario.recreate()
        awaitText(placing)
        click("Cancel")
        // Back at its place, so the page has opened and makes the final save the teardown waits for.
        awaitReader(12)
    }

    /** Loading pages stay hidden; failed/timed-out pages show their own content but never save a position. */
    @Test
    fun anUnreadyPageIsHiddenUntilFailureOrTimeoutAndOffersNoShow() {
        runBlocking {
            val dao = store.library()
            val path = "test-only/unready.md"
            dao.replaceDocuments(
                7,
                listOf(
                    DocumentRow(1, 7, "test-only/lifecycle.md", title, sha),
                    DocumentRow(2, 7, path, "Test-only unready", sha),
                ),
            )
            // No reader script, so the page never says it is ready.
            val html =
                """<main id="note"><p data-block-id="b0" data-anchor-text="Never ready.">Never ready.</p></main>"""
            dao.saveNoteWithoutSearch(
                NoteRow(
                    2,
                    sha,
                    path,
                    "Test-only unready",
                    html,
                    0,
                    renderFormat = BuildConfig.RENDER_FORMAT,
                    snapshotCommitSha = sha,
                )
            )
            dao.saveAnnotation(
                AnnotationRow(
                    "test-only-unready",
                    null,
                    2,
                    sha,
                    "b0",
                    0,
                    5,
                    "Never",
                    null,
                    1,
                    0,
                    AnnotationState.UNSENT,
                )
            )
        }
        // Away from the top, so finding the place again shows the fixture's page has opened.
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        try {
            for (failed in listOf(true, false)) {
                var previous: WebView? = null
                scenario.onActivity {
                    previous = findWebView(it.window.decorView)
                    open(Screen.Reader(2, "Test-only unready"))
                }
                val page = runBlocking {
                    withTimeout(10_000) {
                        while (true) {
                            var current: WebView? = null
                            scenario.onActivity { current = findWebView(it.window.decorView) }
                            if (current != null && current !== previous) return@withTimeout checkNotNull(current)
                            delay(20)
                        }
                        error("Unreachable")
                    }
                }
                scenario.onActivity { assertEquals("Loading page must not flash its top", 0f, page.alpha) }
                awaitText("Opening note…")
                awaitText("Test-only unready")
                click("Highlights and notes")
                assertFalse("Show is offered before the page is ready", control("Show").isEnabled)
                if (failed) evaluate("document.body.dataset.state = 'failed'; return {};")
                runBlocking {
                    withTimeout(20_000) {
                        while (withContext(Dispatchers.Main) { page.alpha != 1f }) delay(50)
                    }
                    assertNull("An unrendered page saves no position", store.library().reading(2))
                }
                if (!failed)
                    awaitText(
                        "This note did not finish rendering, so highlights and your reading position are off here. Reopen it to try again."
                    )
                assertFalse("Show is offered after a rendering failure", control("Show").isEnabled)
            }
        } finally {
            // Back to the fixture's note, whose final save the teardown waits for.
            scenario.onActivity { open(Screen.Reader(1, title)) }
            awaitReader(12)
        }
    }

    @Test
    fun ordinaryReadingSurvivesActivityRecreationTwiceAndSavesInRoom() {
        val position = evaluate("window.reporead.showBlock('b12'); return window.reporead.position();")
        assertEquals(12, position.getJSONObject("anchor").getInt("blockIndex"))
        scenario.recreate()
        awaitReader(12)
        awaitSaved(12)
        evaluate("window.reporead.showBlock('b20'); return {};")
        awaitSaved(20)
        scenario.recreate()
        awaitReader(20)
        awaitSaved(20)
    }

    private fun awaitSaved(block: Int) = runBlocking {
        withTimeout(5_000) {
            while (true) {
                val row = store.library().reading(1)
                if (row != null && JSONObject(row.anchorJson).getInt("blockIndex") == block) {
                    assertEquals(sha, row.lastReadBlobSha)
                    assertTrue(row.pending)
                    break
                }
                delay(10)
            }
        }
    }

    /**
     * The saved position stays at [block] for a second. A save after opening or leaving lands within a few frames, so
     * one that should not happen shows well inside that.
     */
    private fun assertSavedStays(block: Int) = runBlocking {
        repeat(20) {
            val row = checkNotNull(store.library().reading(1))
            assertEquals("Saved position changed: $row", block, JSONObject(row.anchorJson).getInt("blockIndex"))
            delay(50)
        }
    }

    private fun awaitReader(block: Int) = runBlocking {
        var last = JSONObject()
        var visible = false
        repeat(100) {
            last =
                evaluate(
                    """
                    if (!window.reporead || document.body.dataset.state !== 'ready') return {};
                    return {block: window.reporead.position().anchor.blockIndex,
                        canonical: [...document.querySelectorAll('#note [data-block-id]')].every(b => b.textContent === b.dataset.anchorText)};
                    """
                        .trimIndent(),
                    allowLoading = true,
                )
            scenario.onActivity { visible = findWebView(it.window.decorView)?.alpha == 1f }
            if (last.optInt("block", -1) == block && visible) {
                assertTrue("Canonical text changed: $last", last.getBoolean("canonical"))
                scenario.onActivity {
                    assertEquals(
                        "Positioned reader must be visible",
                        1f,
                        checkNotNull(findWebView(it.window.decorView)).alpha,
                    )
                }
                return@runBlocking
            }
            delay(100)
        }
        throw AssertionError("Reader did not restore block=$block visibly; visible=$visible last=$last")
    }

    /** The block at the top once the highlight [key] is drawn and within the screen. */
    private fun awaitHighlightInView(key: String): Int = runBlocking {
        var last = JSONObject()
        repeat(100) {
            last =
                evaluate(
                    """
                    if (!window.reporead || document.body.dataset.state !== 'ready') return {};
                    const mark = document.querySelector('#note mark[data-key="$key"]');
                    if (!mark) return {};
                    const rect = mark.getBoundingClientRect();
                    return {inView: rect.top >= 0 && rect.bottom <= window.innerHeight,
                        block: window.reporead.position().anchor.blockIndex};
                    """
                        .trimIndent(),
                    allowLoading = true,
                )
            if (last.optBoolean("inView")) return@runBlocking last.getInt("block")
            delay(100)
        }
        throw AssertionError("Highlight $key never came into view; last=$last")
    }

    private fun evaluate(script: String, allowLoading: Boolean = false): JSONObject {
        val done = CountDownLatch(1)
        var encoded: String? = null
        scenario.onActivity { activity ->
            val view = findWebView(activity.window.decorView)
            if (view == null && allowLoading) {
                encoded = "\"{}\""
                done.countDown()
            } else {
                checkNotNull(view) { "Reader WebView is missing" }
                    .evaluateJavascript(
                        """JSON.stringify((() => {
                    try { $script } catch (error) { return {error: error.stack}; }
                })())"""
                    ) {
                        encoded = it
                        done.countDown()
                    }
            }
        }
        assertTrue("Reader JavaScript timed out: $script", done.await(5, TimeUnit.SECONDS))
        val result = JSONObject(JSONTokener(encoded).nextValue() as String)
        check(!result.has("error")) { "Reader JavaScript failed: $result" }
        return result
    }
}
