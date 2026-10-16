package com.reporead.android.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.core.network.Api
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.RENDER_FORMAT
import com.reporead.android.sync.Sync
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var scenario: ActivityScenario<ReaderTestActivity>
    private val showingReader = mutableStateOf(true)
    private val title = "Test-only lifecycle fixture"
    /** The screen shown; a new one is a new reader screen, as when the app navigates. */
    private val screenUnderTest = mutableStateOf(Screen.Reader(1, title))
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
        val path = "test-only/lifecycle.md"
        fun block(id: Int, tag: String, text: String) =
            """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""
        val body =
            block(0, "h1", title) +
                block(1, "h2", "Start") +
                (2..5).joinToString("") { block(it, "p", "Test-only opening $it.") } +
                block(6, "h2", "Answer") +
                (7..60).joinToString("") { block(it, "p", "Test-only paragraph $it. ".repeat(20)) } +
                // A same-note link, and a block whose 64-unit prefix would end inside an emoji.
                """<p data-block-id="b61" data-anchor-text="Back to the answer"><a href="#answer">Back to the answer</a></p>""" +
                block(62, "p", "x".repeat(63) + "\uD83D\uDE00 after the emoji") +
                // Enough text below, so those blocks can reach the top of the screen.
                (63..80).joinToString("") { block(it, "p", "Test-only closing paragraph $it. ".repeat(20)) }
        val html =
            """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="/assets/reader.css"><script defer src="/assets/reader.js"></script></head>
            <body data-source-blob-sha="$sha" data-max-diagram-chars="20000" data-max-edges="200">
            <p id="render-status"></p><main id="note">$body</main></body></html>"""
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, path, title, sha)))
        dao.saveNote(NoteRow(1, sha, path, title, html, 0, renderFormat = RENDER_FORMAT))
        val api = Api("http://127.0.0.1:1", { error("Lifecycle fixture must not call the network") }) {}
        val sync = Sync(api, store, files) {}
        ReaderTestActivity.content = {
            MaterialTheme {
                if (showingReader.value)
                    key(screenUnderTest.value) {
                        ReaderScreen(
                            sync,
                            dao,
                            appScope,
                            signedIn = false,
                            screen = screenUnderTest.value,
                            onBack = {},
                            push = { error("Lifecycle fixture must not navigate") },
                        )
                    }
            }
        }
        scenario = ActivityScenario.launch(ReaderTestActivity::class.java)
        awaitReader(0)
        awaitSaved(0)
        readerReady = true
    }

    @After
    fun close() = runBlocking {
        if (readerReady) {
            val lastSavedAt = checkNotNull(store.library().reading(1)).lastReadAt
            // ActivityScenario.close does not await WebView callbacks or Room writes. Dispose the reader first and
            // observe its final save before closing the database.
            scenario.onActivity { showingReader.value = false }
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
        ReaderTestActivity.content = null
        appScope.cancel()
        if (::store.isInitialized) store.close()
        if (::files.isInitialized) check(files.deleteRecursively()) { "Cannot delete test-only directory $files" }
    }

    @Test
    fun aLinkedHeadingIsShownOnceAndRecreationRestoresTheReadingPositionInstead() {
        scenario.onActivity { screenUnderTest.value = Screen.Reader(1, title, OpenAt.Heading("Answer")) }
        awaitReader(6)
        awaitSaved(6)
        evaluate("window.reporead.showBlock('b12'); return {};")
        awaitSaved(12)
        scenario.recreate()
        awaitReader(12)
        awaitSaved(12)
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
            screenUnderTest.value = Screen.Reader(1, title, OpenAt.Highlight("test-only-highlight"))
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
            screenUnderTest.value = Screen.Reader(1, title, OpenAt.Heading("Test-only heading that is gone"))
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

    private fun awaitReader(block: Int) = runBlocking {
        var last = JSONObject()
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
            if (last.optInt("block", -1) == block) {
                assertTrue("Canonical text changed: $last", last.getBoolean("canonical"))
                return@runBlocking
            }
            delay(100)
        }
        throw AssertionError("Reader did not restore block=$block; last=$last")
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

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let {
                    return it
                }
            }
        return null
    }
}
