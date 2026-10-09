package com.reporead.android.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.Screen
import com.reporead.android.core.network.Api
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.RENDER_FORMAT
import com.reporead.android.sync.Sync
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real ReaderScreen, Activity teardown and Room writes; isolated test-only data, zero network calls. */
@RunWith(AndroidJUnit4::class)
class ReaderLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sha = "a".repeat(40)
    private lateinit var store: LocalStore
    private lateinit var files: File
    private lateinit var scenario: ActivityScenario<ReaderTestActivity>
    private val showingReader = mutableStateOf(true)
    private var readerReady = false

    @Before fun open() = runBlocking {
        val context = instrumentation.targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        files = File(context.cacheDir, "reader-lifecycle-test")
        check(files.mkdirs() || files.isDirectory) { "Cannot create test-only directory $files" }
        val dao = store.library()
        val path = "test-only/lifecycle.md"
        val title = "Test-only lifecycle fixture"
        fun block(id: Int, tag: String, text: String) = """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""
        val body = block(0, "h1", title) + block(1, "h2", "Questions this file answers") + "<ul>" +
            block(2, "li", "An identical question?") + block(3, "li", "An identical question?") + "</ul>" +
            block(4, "h2", "Answer") + (5..60).joinToString("") { block(it, "p", "Test-only paragraph $it. ".repeat(20)) }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="/assets/reader.css"><script defer src="/assets/reader.js"></script></head>
            <body data-source-blob-sha="$sha" data-max-diagram-chars="20000" data-max-edges="200">
            <p id="render-status"></p><main id="note">$body</main></body></html>"""
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, path, title, sha)))
        dao.saveNote(NoteRow(1, sha, sha, path, title, html, 0, renderFormat = RENDER_FORMAT))
        val api = Api("http://127.0.0.1:1") { error("Lifecycle fixture must not call the network") }
        val sync = Sync(api, store, files)
        ReaderTestActivity.content = {
            MaterialTheme {
                val activityScope = rememberCoroutineScope()
                if (showingReader.value) {
                    ReaderScreen(sync, dao, activityScope, signedIn = false, onFailure = { throw it },
                        screen = Screen.Reader(1, title, question = StudyTarget(sha, "b2")),
                        onBack = {}, push = { error("Lifecycle fixture must not navigate") })
                }
            }
        }
        scenario = ActivityScenario.launch(ReaderTestActivity::class.java)
        awaitReader(2, recall = true)
        awaitSaved(2)
        readerReady = true
    }

    @After fun close() = runBlocking {
        if (readerReady) {
            val lastSavedAt = checkNotNull(store.library().reading(1)).lastReadAt
            // ActivityScenario.close does not await WebView callbacks or Room writes. Dispose the reader first,
            // while its Activity scope is alive, and observe the final save before closing the database.
            scenario.onActivity { showingReader.value = false }
            withTimeout(5_000) {
                while (checkNotNull(store.library().reading(1)).lastReadAt <= lastSavedAt) delay(10)
            }
        }
        if (::scenario.isInitialized) scenario.close()
        instrumentation.waitForIdleSync()
        ReaderTestActivity.content = null
        if (::store.isInitialized) store.close()
        if (::files.isInitialized) check(files.deleteRecursively()) { "Cannot delete test-only directory $files" }
    }

    @Test fun questionsSurviveActivityRecreationTwiceAndSaveInRoom() {
        evaluate("document.querySelector('.study-next').click(); return window.reporead.position();")
        scenario.recreate()
        awaitReader(3, recall = true)
        awaitSaved(3)
        evaluate("document.querySelector('.study-previous').click(); return window.reporead.position();")
        scenario.recreate()
        awaitReader(2, recall = true)
        awaitSaved(2)
    }

    @Test fun ordinaryReadingSurvivesActivityRecreation() {
        val position = evaluate("document.querySelector('.study-answer').click(); window.reporead.showBlock('b12'); return window.reporead.position();")
        assertEquals(12, position.getJSONObject("anchor").getInt("blockIndex"))
        scenario.recreate()
        awaitReader(12, recall = false)
        awaitSaved(12)
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

    private fun awaitReader(block: Int, recall: Boolean) = runBlocking {
        var last = JSONObject()
        repeat(100) {
            last = evaluate("""
                if (!window.reporead || !document.querySelector('#study-questions')) return {};
                return {block: window.reporead.position().anchor.blockIndex,
                    recall: getComputedStyle(document.getElementById('note')).display === 'none',
                    canonical: [...document.querySelectorAll('#note [data-block-id]')].every(b => b.textContent === b.dataset.anchorText)};
            """.trimIndent(), allowLoading = true)
            if (last.optInt("block", -1) == block && last.optBoolean("recall") == recall) {
                assertTrue("Canonical text changed: $last", last.getBoolean("canonical"))
                return@runBlocking
            }
            delay(100)
        }
        throw AssertionError("Reader did not restore block=$block recall=$recall; last=$last")
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
                checkNotNull(view) { "Reader WebView is missing" }.evaluateJavascript("""JSON.stringify((() => {
                    try { $script } catch (error) { return {error: error.stack}; }
                })())""") { encoded = it; done.countDown() }
            }
        }
        assertTrue("Reader JavaScript timed out: $script", done.await(5, TimeUnit.SECONDS))
        val result = JSONObject(JSONTokener(encoded).nextValue() as String)
        check(!result.has("error")) { "Reader JavaScript failed: $result" }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
